package com.example.database.storage;

import com.example.database.storage.catalog.CatalogManager;

/**
 * On-disk storage with its own lifecycle, owned by {@code DatabaseServer}.
 * Shared collaborator: query processor and other modules may use the same instance.
 * Started before the network accepts traffic; stopped after the network shuts down.
 * <p>
 * Owns:
 * <pre>
 *   CatalogManager      table/schema metadata (owns CatalogStore)
 *   PhysicalStorage     actual physical persistence (wired)
 *   TableStore          actual table data (later)
 *   IndexStore          index structures (later)
 *   LockManager         concurrency locks (later)
 *   TransactionManager  transaction lifecycle (later)
 *   WALManager          durable change logging (later)
 *   BufferPool          cached persistent blocks/pages (later)
 * </pre>
 */
public interface StorageEngine {

    /** Prepare storage resources (idempotent). Loads catalog from disk. */
    void start();

    /** Release storage resources (idempotent). */
    void stop();

    /** Store root this engine was constructed with. */
    DataDirectory dataDirectory();

    /**
     * In-memory catalog, populated on {@link #start()}.
     *
     * @throws IllegalStateException if storage is not started
     */
    CatalogManager catalogManager();
}
