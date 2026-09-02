package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.CatalogSnapshot;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.undo.UndoManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Implicit single-statement and explicit {@code BEGIN} sessions. WAL records carry
 * {@code txnId}; durable flush happens at commit with a trailing {@code COMMIT} record.
 * <p>
 * DML rollback uses {@link UndoManager}; explicit sessions snapshot catalog only at
 * {@code BEGIN} and take the catalog lock only on {@link #commitExplicit}.
 */
public final class DefaultTransactionManager implements TransactionManager {

    private final WALManager walManager;
    private final UndoManager undoManager;
    private final AtomicInteger nextTxnId = new AtomicInteger(1);
    private final AtomicInteger activeExplicitSessions = new AtomicInteger(0);
    private final ThreadLocal<TransactionContext> context = ThreadLocal.withInitial(TransactionContext::new);
    private final ThreadLocal<Integer> implicitDepth = ThreadLocal.withInitial(() -> 0);

    public DefaultTransactionManager(WALManager walManager, UndoManager undoManager) {
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager");
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
        ensureNoNestedTransaction();
        implicitDepth.set(1);
        int txnId = allocateTxnId();
        context.get().beginImplicit(txnId);
        undoManager.clear(txnId);
        try {
            walManager.discardPending();
            T result = action.get();
            commitCurrent(txnId);
            undoManager.clear(txnId);
            return result;
        } catch (Throwable t) {
            abortImplicit(txnId, null, null);
            throw t;
        } finally {
            context.get().clear();
            implicitDepth.remove();
        }
    }

    @Override
    public <T> T runInTransaction(LockManager lockManager, TableStore tableStore, Supplier<T> action) {
        Objects.requireNonNull(lockManager, "lockManager");
        Objects.requireNonNull(tableStore, "tableStore");
        Objects.requireNonNull(action, "action");
        ensureNoNestedTransaction();
        implicitDepth.set(1);
        int txnId = allocateTxnId();
        context.get().beginImplicit(txnId);
        undoManager.clear(txnId);
        lockManager.bindOwner(txnId);
        try {
            walManager.discardPending();
            T result = action.get();
            commitCurrent(txnId);
            undoManager.clear(txnId);
            return result;
        } catch (Throwable t) {
            abortImplicit(txnId, lockManager, tableStore);
            throw t;
        } finally {
            lockManager.unlockAllForOwner();
            lockManager.clearOwnerBinding();
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
        catalogManager.setDeferPersist(true);
        context.get().beginExplicit(txnId, catalogSnapshot);
        undoManager.clear(txnId);
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
                undoManager.rollback(txnId, tableStore);
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
                undoManager.rollback(txnId, tableStore);
                walManager.discardPending();
            } else {
                undoManager.clear(txnId);
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
        undoManager.rollback(txnId, tableStore);
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
    public boolean active() {
        return context.get().active();
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

    @Override
    public IsolationLevel isolationLevel() {
        return context.get().isolationLevel();
    }

    @Override
    public void setIsolationLevel(IsolationLevel level) {
        context.get().setIsolationLevel(Objects.requireNonNull(level, "level"));
        if (level == IsolationLevel.REPEATABLE_READ) {
            throw new UnsupportedOperationException("REPEATABLE READ is not implemented yet");
        }
    }

    private void ensureNoNestedTransaction() {
        if (context.get().active()) {
            throw new IllegalStateException("nested transactions are not supported");
        }
        if (implicitDepth.get() > 0) {
            throw new IllegalStateException("nested transactions are not supported");
        }
    }

    private int allocateTxnId() {
        return nextTxnId.getAndIncrement();
    }

    private void commitCurrent(int txnId) {
        walManager.append(WalRecord.commit(txnId));
        walManager.flush();
    }

    private void abortImplicit(int txnId, LockManager lockManager, TableStore tableStore) {
        if (tableStore != null) {
            undoManager.rollback(txnId, tableStore);
        } else {
            undoManager.clear(txnId);
        }
        walManager.discardPending();
        if (lockManager != null) {
            lockManager.unlockAllForOwner();
        }
    }
}
