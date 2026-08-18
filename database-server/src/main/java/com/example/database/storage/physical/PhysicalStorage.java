package com.example.database.storage.physical;

/**
 * Performs actual physical persistence: read/write blocks or files on disk.
 * No catalog or table semantics — only bytes at a location.
 * Used by {@code BufferPool} (and WAL if the log is a separate file).
 */
public interface PhysicalStorage {
}
