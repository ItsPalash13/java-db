package com.example.database.storage.checkpoint;

import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;

import java.util.Objects;

/**
 * Background loop owned by {@code StorageEngine}: wait on a plugged
 * {@link CheckpointStrategy}, then run the shared durable-only
 * {@link WALManager#checkpoint()} under the exclusive catalog lock.
 * <p>
 * Keeps timing policy out of {@code DefaultWALManager} (I/O only) and out of
 * SQL executors (request path). Manual {@code CHECKPOINT} bypasses this loop and
 * calls the same {@code checkpoint()} from {@code CheckpointExecutor}.
 */
public final class CheckpointScheduler {

    private final CheckpointStrategy strategy;
    private final LockManager lockManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;
    private final Object monitor = new Object();
    private Thread worker;
    private volatile boolean running;

    public CheckpointScheduler(
            CheckpointStrategy strategy,
            LockManager lockManager,
            WALManager walManager,
            TransactionManager transactionManager
    ) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
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
                // Wakes Thread.sleep / poll sleep in the plugged strategy.
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
                // Skip while any connection has an open BEGIN — deferred catalog / WAL must
                // not be truncated before those sessions COMMIT or ROLLBACK.
                if (transactionManager.activeExplicitSessionCount() > 0) {
                    continue;
                }
                lockManager.runExclusiveCatalog(walManager::checkpoint);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Swallow and retry on the next trigger — a single I/O blip should not
                // kill the daemon for the life of the process.
                System.err.println("[CheckpointScheduler] checkpoint failed: " + e.getMessage());
            }
        }
    }
}
