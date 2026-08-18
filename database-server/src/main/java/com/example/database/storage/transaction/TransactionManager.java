package com.example.database.storage.transaction;

/**
 * Owns transaction lifecycle: begin, commit, abort.
 * Coordinates with {@code LockManager} and {@code WALManager}; does not persist pages itself.
 */
public interface TransactionManager {
}
