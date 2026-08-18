package com.example.database.storage.bufferpool;

/**
 * Cached persistent blocks/pages in memory.
 * Callers pin/unpin pages; dirty pages flush through {@code PhysicalStorage}.
 * Catalog, table, and index data share this pool rather than each buffering on their own.
 */
public interface BufferPool {
}
