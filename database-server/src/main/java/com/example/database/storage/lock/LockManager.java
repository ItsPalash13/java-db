package com.example.database.storage.lock;

import java.util.function.Supplier;

/**
 * Owns concurrency locks so readers and writers do not clash on shared storage.
 * Step 2: catalog exclusive only ({@link #runExclusiveCatalog}). Table S/X and
 * row locks come later with DML.
 */
public interface LockManager {

    /**
     * Runs {@code action} while holding the exclusive catalog lock (blocking).
     * Serializes all DDL that mutates schema / catalog files.
     */
    void runExclusiveCatalog(Runnable action);

    /**
     * Same as {@link #runExclusiveCatalog(Runnable)} but returns the supplier result.
     */
    <T> T runExclusiveCatalog(Supplier<T> action);

    /**
     * Acquires the catalog lock for an explicit transaction that spans multiple statements.
     * Must pair with {@link #unlockExclusiveCatalog()}.
     */
    void lockExclusiveCatalog();

    /**
     * Non-blocking attempt to take the catalog lock for explicit {@code BEGIN}.
     *
     * @return {@code true} if this thread now holds the lock
     */
    boolean tryLockExclusiveCatalog();

    /** Releases the catalog lock taken by {@link #lockExclusiveCatalog()}. */
    void unlockExclusiveCatalog();
}
