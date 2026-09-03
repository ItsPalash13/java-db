package com.example.database.storage;

import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.table.TableStore;
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
 *   WALManager          durable catalog DDL logging + replay + checkpoint (wired)
 *   CheckpointScheduler background timeout/size triggers (wired when enabled)
 *   TableStore          row heaps ({@link com.example.database.storage.table.FileTableStore})
 *   IndexStore          on-disk B+ tree indexes ({@code .idx})
 *   BufferPool          cached persistent pages for .ibd/.idx (wired)
 * </pre>
 */
public interface StorageEngine {

    /** Prepare storage resources (idempotent). Loads catalog from disk, then replays WAL. */
    void start();

    /** Release storage resources (idempotent). Flushes dirty buffer-pool pages, then stops the checkpoint scheduler. */
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

    /**
     * Row store for DML/DQL. Page-backed {@code .ibd} heaps through {@link #bufferPool()}.
     *
     * @throws IllegalStateException if storage is not started
     */
    TableStore tableStore();

    /**
     * Secondary B+ tree indexes for equality probes and DML maintenance.
     *
     * @throws IllegalStateException if storage is not started
     */
    IndexStore indexStore();

    /**
     * Page cache for heap {@code .ibd} / index {@code .idx} files.
     * Constructed with the engine and flushed on {@link #stop()}; {@link #tableStore()} pins through it.
     * Volcano operators must never call {@code pin} directly.
     *
     * @throws IllegalStateException if storage is not started
     */
    BufferPool bufferPool();
}
