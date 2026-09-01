package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.table.TableStore;

import java.util.function.Supplier;

/**
 * Owns transaction lifecycle for one unit of work (begin → work → commit or rollback).
 * Implicit single-statement txns via {@link #runInTransaction}; explicit sessions via
 * {@link #beginExplicit} / {@link #commitExplicit} / {@link #rollbackExplicit}.
 */
public interface TransactionManager {

    /**
     * Runs {@code action} in one implicit transaction: begin, work, commit on success,
     * rollback then rethrow on failure. Nested calls are rejected.
     */
    void runInTransaction(Runnable action);

    /**
     * Same as {@link #runInTransaction(Runnable)} but returns the supplier result.
     */
    <T> T runInTransaction(Supplier<T> action);

    /**
     * Seeds the next txn id after WAL replay on storage start.
     */
    void seedNextTxnId(int nextTxnId);

    /**
     * Starts an explicit client transaction on this thread. Defers catalog.json writes until
     * {@link #commitExplicit}; does not hold the catalog lock for the whole session so other
     * connections can {@code BEGIN} concurrently.
     *
     * @throws IllegalStateException if a transaction is already active on this thread
     */
    void beginExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    void commitExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    void rollbackExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    /**
     * Client disconnected: roll back an open explicit txn on this thread and release
     * scoped locks. Safe to call when no transaction is active.
     */
    void endConnectionSession(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    /** Whether this thread is inside an explicit {@code BEGIN} session. */
    boolean inExplicitTransaction();

    /** Open explicit sessions anywhere in the process (used to defer CHECKPOINT). */
    int activeExplicitSessionCount();

    /**
     * Active txn id for WAL append on this thread, or throws if none.
     */
    int currentTxnId();
}
