package com.example.database.storage.lock;

import java.util.function.Supplier;

/**
 * Owns concurrency locks so readers and writers do not clash on shared storage.
 * Step 2: catalog exclusive only ({@link #runExclusiveCatalog}). Table S/X and
 * row locks come later with DML.
 */
public interface LockManager {

    /**
     * Runs {@code action} while holding the exclusive catalog lock. Waits up to the
     * configured lock timeout, then throws {@link CatalogLockException}.
     */
    void runExclusiveCatalog(Runnable action);

    /**
     * Same as {@link #runExclusiveCatalog(Runnable)} but returns the supplier result.
     */
    <T> T runExclusiveCatalog(Supplier<T> action);

    /**
     * Waits for the catalog lock for an explicit transaction that spans multiple statements.
     * Must pair with {@link #unlockExclusiveCatalog()}.
     *
     * @throws CatalogLockException if the wait exceeds the configured timeout
     */
    void lockExclusiveCatalog();

    /** Releases the catalog lock taken by {@link #lockExclusiveCatalog()}. */
    void unlockExclusiveCatalog();
}
