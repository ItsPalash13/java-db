package com.example.database.storage.table;

/**
 * Owns actual table data (rows / pages for user tables).
 * Does not own schema — that is {@code CatalogManager}.
 * Reads and writes go through {@code BufferPool} / {@code PhysicalStorage}.
 */
public interface TableStore {
}
