package com.example.database.storage.lock;

/**
 * Owns concurrency locks (table-level first; row-level later if needed).
 * Used by the transaction / query path so readers and writers do not clash on the same table.
 */
public interface LockManager {
}
