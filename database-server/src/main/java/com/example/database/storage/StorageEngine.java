package com.example.database.storage;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;

/**
 * On-disk storage with its own lifecycle, owned by {@code DatabaseServer}.
 * Shared collaborator: query processor and other modules may use the same instance.
 * Started before the network accepts traffic; stopped after the network shuts down.
 * <p>
 * Owns:
 * <pre>
 *   CatalogManager      table/schema metadata (owns CatalogStore)
 *   PhysicalStorage     actual physical persistence (wired)
 *   TransactionManager  implicit single-statement DDL transactions (wired)
 *   LockManager         catalog exclusive locks for DDL (wired)
 *   WALManager          durable catalog DDL logging + replay (wired)
 *   TableStore          actual table data (later)
 *   IndexStore          index structures (later)
 *   BufferPool          cached persistent blocks/pages (later)
 * </pre>
 */
public interface StorageEngine {

    /** Prepare storage resources (idempotent). Loads catalog from disk, then replays WAL. */
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

    /**
     * Transaction orchestrator for DDL (and later DML). Available after {@link #start()}.
     *
     * @throws IllegalStateException if storage is not started
     */
    TransactionManager transactionManager();

    /**
     * Concurrency locks. Available after {@link #start()}.
     *
     * @throws IllegalStateException if storage is not started
     */
    LockManager lockManager();

    /**
     * Write-ahead log. Available after {@link #start()}.
     *
     * @throws IllegalStateException if storage is not started
     */
    WALManager walManager();
}
