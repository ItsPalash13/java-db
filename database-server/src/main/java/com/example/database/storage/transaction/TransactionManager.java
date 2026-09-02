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
     * Implicit txn with lock release after commit/abort. Volcano DML uses this path.
     */
    <T> T runInTransaction(LockManager lockManager, TableStore tableStore, Supplier<T> action);

    /**
     * Seeds the next txn id after WAL replay on storage start.
     */
    void seedNextTxnId(int nextTxnId);

    void beginExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    void commitExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    void rollbackExplicit(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    void endConnectionSession(LockManager lockManager, CatalogManager catalogManager, TableStore tableStore);

    boolean inExplicitTransaction();

    /** Whether this thread has an implicit or explicit transaction open. */
    boolean active();

    int activeExplicitSessionCount();

    int currentTxnId();

    IsolationLevel isolationLevel();

    void setIsolationLevel(IsolationLevel level);
}
