package com.example.database.storage.checkpoint;

import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;

import java.util.Objects;

/**
 * Background loop owned by {@code StorageEngine}: wait on a plugged
 * {@link CheckpointStrategy}, then under ENGINE X flush WAL, dirty pages, and
 * the durable-only {@link WALManager#checkpoint()} fence.
 */
public final class CheckpointScheduler {

    private final CheckpointStrategy strategy;
    private final LockManager lockManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;
    private final BufferPool bufferPool;
    private final Object monitor = new Object();
    private Thread worker;
    private volatile boolean running;

    public CheckpointScheduler(
            CheckpointStrategy strategy,
            LockManager lockManager,
            WALManager walManager,
            TransactionManager transactionManager,
            BufferPool bufferPool
    ) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
    }

    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            // Daemon: a forgotten stop() in tests must not pin the JVM open forever.
            worker = new Thread(this::loop, "checkpoint-scheduler");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void stop() {
        Thread toJoin;
        synchronized (monitor) {
            if (!running) {
                return;
            }
            running = false;
            toJoin = worker;
            worker = null;
            if (toJoin != null) {
                toJoin.interrupt();
            }
        }
        if (toJoin != null) {
            try {
                toJoin.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        while (running) {
            try {
                strategy.awaitTrigger();
                if (!running) {
                    return;
                }
                if (transactionManager.activeExplicitSessionCount() > 0) {
                    continue;
                }
                lockManager.runWithEngineX(() -> lockManager.runExclusiveCatalog(() -> {
                    walManager.flush();
                    bufferPool.flushAll();
                    walManager.checkpoint();
                }));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("[CheckpointScheduler] checkpoint failed: " + e.getMessage());
            }
        }
    }
}
