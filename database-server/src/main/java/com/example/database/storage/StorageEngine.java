package com.example.database.storage;

/**
 * On-disk storage with its own lifecycle, owned by {@code DatabaseServer}.
 * Shared collaborator: query processor and other modules may use the same instance.
 * Started before the network accepts traffic; stopped after the network shuts down.
 * <p>
 * Owns storage submodules (wired later; not constructed yet):
 * <pre>
 *   CatalogManager      table/schema metadata
 *   TableStore          actual table data
 *   IndexStore          index structures
 *   LockManager         concurrency locks
 *   TransactionManager  transaction lifecycle
 *   WALManager          durable change logging
 *   BufferPool          cached persistent blocks/pages
 *   PhysicalStorage     actual physical persistence
 * </pre>
 * Stub today: holds the store root ({@link DataDirectory}) only.
 */
public interface StorageEngine {

    /** Prepare storage resources (idempotent). */
    void start();

    /** Release storage resources (idempotent). */
    void stop();

    /** Store root this engine was constructed with. */
    DataDirectory dataDirectory();
}
