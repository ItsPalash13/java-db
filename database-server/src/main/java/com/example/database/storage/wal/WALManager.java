package com.example.database.storage.wal;

/**
 * Owns durable change logging (write-ahead log).
 * Records intent before pages are flushed so crash recovery can replay or undo.
 */
public interface WALManager {
}
