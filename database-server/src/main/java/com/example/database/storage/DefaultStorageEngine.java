package com.example.database.storage;

import com.example.database.config.ServerEnvironment;
import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.checkpoint.CheckpointScheduler;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.table.InMemoryTableStore;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.table.UndoableTableStore;
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
 * {@link CatalogManager}, not here. DML still uses {@link InMemoryTableStore};
 * the pool is ready for Phase 4 FileTableStore.
 */
public final class DefaultStorageEngine implements StorageEngine {

    private final DataDirectory dataDirectory;
    private final PhysicalStorage physicalStorage;
    private final DefaultCatalogManager catalogManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;
    private final LockManager lockManager;
    private final TableStore tableStore;
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
        this.physicalStorage = new DefaultPhysicalStorage(dataDirectory);
        this.catalogManager = new DefaultCatalogManager(physicalStorage);
        this.walManager = new DefaultWALManager(physicalStorage);
        UndoManager undoManager = new DefaultUndoManager();
        this.transactionManager = new DefaultTransactionManager(walManager, undoManager);
        this.lockManager = new DefaultLockManager(environment.catalogLockWait());
        InMemoryTableStore heap = new InMemoryTableStore();
        this.tableStore = new UndoableTableStore(heap, undoManager, transactionManager);
        // Shared by future .ibd heap and .idx trees; Volcano must not call pin.
        this.bufferPool = new DefaultBufferPool(physicalStorage);
        this.checkpointEnabled = environment.checkpointEnabled();
        // Strategy is chosen once at construction (timeout XOR wal_size). SQL CHECKPOINT
        // does not go through this scheduler — CheckpointExecutor calls walManager directly.
        this.checkpointScheduler = new CheckpointScheduler(
                environment.createCheckpointStrategy(physicalStorage),
                lockManager,
                walManager,
                transactionManager
        );
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

    /**
     * Shared page cache for future {@code .ibd}/{@code .idx} I/O.
     * Available after {@link #start()}; DML does not pin through it yet.
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
        // Disk snapshot first; WAL then fills any committed intent that never landed in catalog.json.
        // Replay also reads wal.checkpoint so maxTxnId survives a prior truncate.
        catalogManager.load();
        int maxTxnId = walManager.replay(catalogManager);
        transactionManager.seedNextTxnId(maxTxnId + 1);
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
        // Clean shutdown: persist any dirty pages so a restart does not rely on redo (none yet).
        bufferPool.flushAll();
        System.out.println("[StorageEngine] stopped");
    }

    private void requireStarted() {
        if (!running.get()) {
            throw new IllegalStateException("storage is not started");
        }
    }
}
