package com.example.database.storage.lock;

import java.util.function.Supplier;

/**
 * Owns concurrency locks so readers and writers do not clash on shared storage.
 * Catalog exclusive for CHECKPOINT / CREATE DATABASE; scoped database / table / row
 * locks for DML and table DDL.
 */
public interface LockManager {

    void runExclusiveCatalog(Runnable action);

    <T> T runExclusiveCatalog(Supplier<T> action);

    void lockExclusiveCatalog();

    void unlockExclusiveCatalog();

    /**
     * Binds the current thread's lock owner id (usually {@code txnId}) for scoped acquire.
     * Cleared by {@link #clearOwnerBinding()} after a statement.
     */
    void bindOwner(long ownerId);

    void clearOwnerBinding();

    <T> T runWithTable(String database, String table, LockMode tableMode, Supplier<T> action);

    void runWithTable(String database, String table, LockMode tableMode, Runnable action);

    <T> T runWithDatabase(String database, LockMode mode, Supplier<T> action);

    void runWithDatabase(String database, LockMode mode, Runnable action);

    void lockTable(String database, String table, LockMode tableMode);

    void unlockTable(String database, String table, LockMode tableMode);

    void lockRow(String database, String table, long rowId, LockMode mode);

    /** Whether the bound owner already holds row X (e.g. from an earlier statement in the same txn). */
    boolean holdsRowExclusive(String database, String table, long rowId);

    void unlockRow(String database, String table, long rowId, LockMode mode);

    /** Drops every scoped lock held by the bound owner on this thread. */
    void unlockAllForOwner();

    /**
     * READ COMMITTED: release shared locks (S, IS) at statement end while keeping X/IX
     * until {@link #unlockAllForOwner()} on COMMIT/ABORT.
     */
    void unlockSharedForOwner();
}
