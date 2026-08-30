package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.CatalogSnapshot;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Implicit single-statement and explicit {@code BEGIN} sessions. WAL records carry
 * {@code txnId}; durable flush happens at commit with a trailing {@code COMMIT} record.
 */
public final class DefaultTransactionManager implements TransactionManager {

    private final WALManager walManager;
    private final AtomicInteger nextTxnId = new AtomicInteger(1);
    private final ThreadLocal<TransactionContext> context = ThreadLocal.withInitial(TransactionContext::new);
    // Per-thread depth: one implicit txn at a time; explicit sessions reject nested runInTransaction.
    private final ThreadLocal<Integer> implicitDepth = ThreadLocal.withInitial(() -> 0);

    public DefaultTransactionManager(WALManager walManager) {
        this.walManager = Objects.requireNonNull(walManager, "walManager");
    }

    @Override
    public void seedNextTxnId(int nextTxnId) {
        if (nextTxnId < 1) {
            this.nextTxnId.set(1);
            return;
        }
        this.nextTxnId.set(nextTxnId);
    }

    @Override
    public void runInTransaction(Runnable action) {
        Objects.requireNonNull(action, "action");
        runInTransaction(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (context.get().active()) {
            throw new IllegalStateException("nested transactions are not supported");
        }
        if (implicitDepth.get() > 0) {
            throw new IllegalStateException("nested transactions are not supported");
        }
        implicitDepth.set(1);
        int txnId = allocateTxnId();
        context.get().beginImplicit(txnId);
        try {
            walManager.discardPending();
            T result = action.get();
            commitCurrent(txnId);
            return result;
        } catch (Throwable t) {
            rollbackCurrent();
            throw t;
        } finally {
            context.get().clear();
            implicitDepth.remove();
        }
    }

    @Override
    public void beginExplicit(LockManager lockManager, CatalogManager catalogManager) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        if (context.get().active()) {
            throw new IllegalStateException("transaction already active");
        }
        lockManager.lockExclusiveCatalog();
        try {
            int txnId = allocateTxnId();
            CatalogSnapshot snapshot = catalogManager.snapshot();
            catalogManager.setDeferPersist(true);
            context.get().beginExplicit(txnId, snapshot);
            walManager.discardPending();
        } catch (RuntimeException e) {
            catalogManager.setDeferPersist(false);
            lockManager.unlockExclusiveCatalog();
            throw e;
        }
    }

    @Override
    public void commitExplicit(LockManager lockManager, CatalogManager catalogManager) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        TransactionContext session = context.get();
        if (!session.explicitMode()) {
            throw new IllegalStateException("no explicit transaction to commit");
        }
        int txnId = session.txnId();
        CatalogSnapshot before = session.catalogSnapshot();
        try {
            walManager.append(WalRecord.commit(txnId));
            walManager.flush();
            catalogManager.setDeferPersist(false);
            catalogManager.persistChangesSince(before);
        } catch (RuntimeException e) {
            catalogManager.setDeferPersist(false);
            catalogManager.restoreSnapshot(before);
            walManager.discardPending();
            throw e;
        } finally {
            session.clear();
            lockManager.unlockExclusiveCatalog();
        }
    }

    @Override
    public void rollbackExplicit(LockManager lockManager, CatalogManager catalogManager) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        TransactionContext session = context.get();
        if (!session.explicitMode()) {
            throw new IllegalStateException("no explicit transaction to rollback");
        }
        CatalogSnapshot before = session.catalogSnapshot();
        catalogManager.setDeferPersist(false);
        catalogManager.restoreSnapshot(before);
        walManager.discardPending();
        session.clear();
        lockManager.unlockExclusiveCatalog();
    }

    @Override
    public void endConnectionSession(LockManager lockManager, CatalogManager catalogManager) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        if (inExplicitTransaction()) {
            rollbackExplicit(lockManager, catalogManager);
            return;
        }
        context.get().clear();
    }

    @Override
    public boolean inExplicitTransaction() {
        return context.get().explicitMode();
    }

    @Override
    public int currentTxnId() {
        TransactionContext session = context.get();
        if (!session.active()) {
            throw new IllegalStateException("no active transaction");
        }
        return session.txnId();
    }

    private int allocateTxnId() {
        return nextTxnId.getAndIncrement();
    }

    private void commitCurrent(int txnId) {
        walManager.append(WalRecord.commit(txnId));
        walManager.flush();
    }

    private void rollbackCurrent() {
        walManager.discardPending();
    }
}
