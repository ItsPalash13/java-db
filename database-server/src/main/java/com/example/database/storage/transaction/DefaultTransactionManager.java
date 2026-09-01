package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.CatalogSnapshot;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.table.TableSnapshot;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Implicit single-statement and explicit {@code BEGIN} sessions. WAL records carry
 * {@code txnId}; durable flush happens at commit with a trailing {@code COMMIT} record.
 * <p>
 * Explicit sessions defer catalog.json writes and take the catalog lock only on
 * {@link #commitExplicit} (brief persist), not for the whole {@code BEGIN … COMMIT} span.
 * Heap rows are snapshotted at {@link #beginExplicit} and restored on rollback.
 */
public final class DefaultTransactionManager implements TransactionManager {

    private final WALManager walManager;
    private final AtomicInteger nextTxnId = new AtomicInteger(1);
    private final AtomicInteger activeExplicitSessions = new AtomicInteger(0);
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
    public void beginExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        Objects.requireNonNull(tableStore, "tableStore");
        if (context.get().active()) {
            throw new IllegalStateException("transaction already active");
        }
        int txnId = allocateTxnId();
        CatalogSnapshot catalogSnapshot = catalogManager.snapshot();
        TableSnapshot tableSnapshot = tableStore.snapshot();
        catalogManager.setDeferPersist(true);
        context.get().beginExplicit(txnId, catalogSnapshot, tableSnapshot);
        walManager.discardPending();
        activeExplicitSessions.incrementAndGet();
    }

    @Override
    public void commitExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        Objects.requireNonNull(tableStore, "tableStore");
        TransactionContext session = context.get();
        if (!session.explicitMode()) {
            throw new IllegalStateException("no explicit transaction to commit");
        }
        int txnId = session.txnId();
        CatalogSnapshot catalogBefore = session.catalogSnapshot();
        lockManager.bindOwner(txnId);
        boolean persisted = false;
        try {
            lockManager.lockExclusiveCatalog();
            try {
                walManager.append(WalRecord.commit(txnId));
                walManager.flush();
                catalogManager.setDeferPersist(false);
                catalogManager.persistChangesSince(catalogBefore);
                persisted = true;
            } catch (RuntimeException e) {
                catalogManager.setDeferPersist(false);
                catalogManager.restoreSnapshot(catalogBefore);
                tableStore.restoreSnapshot(session.tableSnapshot());
                walManager.discardPending();
                throw e;
            } finally {
                lockManager.unlockExclusiveCatalog();
            }
        } finally {
            lockManager.unlockAllForOwner();
            lockManager.clearOwnerBinding();
            if (!persisted) {
                catalogManager.setDeferPersist(false);
                catalogManager.restoreSnapshot(catalogBefore);
                tableStore.restoreSnapshot(session.tableSnapshot());
                walManager.discardPending();
            }
            session.clear();
            activeExplicitSessions.decrementAndGet();
        }
    }

    @Override
    public void rollbackExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        Objects.requireNonNull(tableStore, "tableStore");
        TransactionContext session = context.get();
        if (!session.explicitMode()) {
            throw new IllegalStateException("no explicit transaction to rollback");
        }
        int txnId = session.txnId();
        catalogManager.setDeferPersist(false);
        catalogManager.restoreSnapshot(session.catalogSnapshot());
        tableStore.restoreSnapshot(session.tableSnapshot());
        walManager.discardPending();
        lockManager.bindOwner(txnId);
        try {
            lockManager.unlockAllForOwner();
        } finally {
            lockManager.clearOwnerBinding();
            session.clear();
            activeExplicitSessions.decrementAndGet();
        }
    }

    @Override
    public void endConnectionSession(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(catalogManager, "catalogManager");
        Objects.requireNonNull(tableStore, "tableStore");
        if (inExplicitTransaction()) {
            rollbackExplicit(lockManager, catalogManager, tableStore);
            return;
        }
        context.get().clear();
    }

    @Override
    public boolean inExplicitTransaction() {
        return context.get().explicitMode();
    }

    @Override
    public int activeExplicitSessionCount() {
        return activeExplicitSessions.get();
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
