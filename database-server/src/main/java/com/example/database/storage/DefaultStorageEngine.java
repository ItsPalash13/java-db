package com.example.database.storage;

import com.example.database.config.ServerEnvironment;
import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.checkpoint.CheckpointScheduler;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.page.PageFileValidator;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.table.FileTableStore;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.table.UndoableTableStore;
import com.example.database.storage.index.FileIndexStore;
import com.example.database.storage.index.IndexMaintainer;
import com.example.database.storage.index.IndexPageWal;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.transaction.DefaultTransactionManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.undo.DefaultUndoManager;
import com.example.database.storage.undo.UndoManager;
import com.example.database.storage.wal.DefaultWALManager;
import com.example.database.storage.wal.WALManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the data directory, physical files, catalog, transactions, locks, WAL,
 * buffer pool, and optional checkpoint scheduler. Catalog JSON lives inside
 * {@link CatalogManager}, not here. DML uses {@link FileTableStore} on disk
 * {@code .ibd} heaps through the shared buffer pool.
 */
public final class DefaultStorageEngine implements StorageEngine {

    private final DataDirectory dataDirectory;
    private final PhysicalStorage physicalStorage;
    private final DefaultCatalogManager catalogManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;
    private final LockManager lockManager;
    private final TableStore tableStore;
    private final IndexStore indexStore;
    private final FileTableStore fileTableStore;
    private final IndexPageWal indexPageWal;
    private final BufferPool bufferPool;
    private final CheckpointScheduler checkpointScheduler;
    // From ServerEnvironment: defaults() leaves this false so unit tests do not start a daemon.
    private final boolean checkpointEnabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DefaultStorageEngine(DataDirectory dataDirectory) {
        this(dataDirectory, ServerEnvironment.defaults());
    }

    public DefaultStorageEngine(DataDirectory dataDirectory, ServerEnvironment environment) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(environment, "environment");
        // PAGE_SIZE from server.env / process env — must match existing .ibd/.idx images.
        this.physicalStorage = new DefaultPhysicalStorage(dataDirectory, environment.pageSize());
        this.catalogManager = new DefaultCatalogManager(physicalStorage);
        this.walManager = new DefaultWALManager(physicalStorage);
        this.bufferPool = new DefaultBufferPool(physicalStorage);
        IndexPageWal indexPageWal = new IndexPageWal(physicalStorage);
        if (bufferPool instanceof DefaultBufferPool defaultBufferPool) {
            defaultBufferPool.setPageFlushHook(indexPageWal::logPageWrite);
            defaultBufferPool.setWalManager(walManager);
        }
        this.indexStore = new FileIndexStore(bufferPool, physicalStorage);
        UndoManager undoManager = new DefaultUndoManager(indexStore);
        this.transactionManager = new DefaultTransactionManager(walManager, undoManager, indexStore);
        this.lockManager = new DefaultLockManager(environment.catalogLockWait());
        IndexMaintainer indexMaintainer = new IndexMaintainer(
                catalogManager,
                indexStore,
                transactionManager,
                walManager
        );
        FileTableStore heap = new FileTableStore(
                catalogManager,
                bufferPool,
                physicalStorage,
                indexMaintainer,
                walManager,
                transactionManager
        );
        this.tableStore = new UndoableTableStore(heap, undoManager, transactionManager);
        // Shared by heap .ibd and future .idx trees; Volcano must not call pin.
        this.checkpointEnabled = environment.checkpointEnabled();
        // Strategy is chosen once at construction (timeout XOR wal_size). SQL CHECKPOINT
        // does not go through this scheduler — CheckpointExecutor calls the same flush order.
        this.checkpointScheduler = new CheckpointScheduler(
                environment.createCheckpointStrategy(physicalStorage),
                lockManager,
                walManager,
                transactionManager,
                bufferPool
        );
        this.fileTableStore = heap;
        this.indexPageWal = indexPageWal;
    }

    @Override
    public DataDirectory dataDirectory() {
        return dataDirectory;
    }

    @Override
    public CatalogManager catalogManager() {
        requireStarted();
        return catalogManager;
    }

    @Override
    public TransactionManager transactionManager() {
        requireStarted();
        return transactionManager;
    }

    @Override
    public LockManager lockManager() {
        requireStarted();
        return lockManager;
    }

    @Override
    public WALManager walManager() {
        requireStarted();
        return walManager;
    }

    @Override
    public TableStore tableStore() {
        requireStarted();
        return tableStore;
    }

    @Override
    public IndexStore indexStore() {
        requireStarted();
        return indexStore;
    }

    /**
     * Shared page cache for {@code .ibd} heap and {@code .idx} index I/O.
     * Available after {@link #start()}; Volcano uses {@link #tableStore()} only.
     */
    @Override
    public BufferPool bufferPool() {
        requireStarted();
        return bufferPool;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dataDirectory.ensureExists();
        // Fail fast if PAGE_SIZE does not match on-disk .ibd / .idx page images.
        PageFileValidator.validateAll(dataDirectory, physicalStorage);
        // Disk snapshot first; WAL then fills any committed intent that never landed in catalog.json.
        // Replay also reads wal.checkpoint so maxTxnId survives a prior truncate.
        catalogManager.load();
        int maxTxnId = walManager.replay(catalogManager);
        // Redo committed logical DML without logging again or double-maintaining indexes.
        fileTableStore.setSuppressSideEffects(true);
        try {
            walManager.redoDml(fileTableStore, indexStore);
        } finally {
            fileTableStore.setSuppressSideEffects(false);
        }
        indexPageWal.replay();
        transactionManager.seedNextTxnId(maxTxnId + 1);
        if (indexStore instanceof FileIndexStore fileIndexStore) {
            for (TableMetadata table : catalogManager.allTables()) {
                fileIndexStore.loadKeyTypesFromCatalog(table);
            }
        }
        if (checkpointEnabled) {
            // After recovery is complete — never checkpoint while still replaying.
            checkpointScheduler.start();
        }
        System.out.println("[StorageEngine] data directory: " + dataDirectory.root());
        System.out.println("[StorageEngine] started");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Always stop: even if never started (checkpointEnabled=false), stop() is idempotent.
        checkpointScheduler.stop();
        // Persist dirty pages; WAL-before-data runs per .ibd frame. Restart can also redo.
        bufferPool.flushAll();
        System.out.println("[StorageEngine] stopped");
    }

    private void requireStarted() {
        if (!running.get()) {
            throw new IllegalStateException("storage is not started");
        }
    }
}
