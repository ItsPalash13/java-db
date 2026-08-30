package com.example.database.storage.checkpoint;

import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.wal.DefaultWALManager;

import java.time.Duration;
import java.util.Objects;

/**
 * Size-based trigger: fire when {@code wal.log} reaches {@code maxWalSizeBytes}
 * (Postgres-style {@code max_wal_size} / segment pressure).
 * <p>
 * Polls instead of listening to every flush: WAL appends are on connection threads;
 * coupling them to the scheduler would blur the strategy boundary. Short poll keeps
 * {@link #awaitTrigger()} interruptible on stop.
 */
public final class WalSizeCheckpointStrategy implements CheckpointStrategy {

    private final PhysicalStorage physicalStorage;
    private final long maxWalSizeBytes;
    private final Duration pollInterval;

    public WalSizeCheckpointStrategy(PhysicalStorage physicalStorage, long maxWalSizeBytes) {
        this(physicalStorage, maxWalSizeBytes, Duration.ofMillis(200));
    }

    public WalSizeCheckpointStrategy(
            PhysicalStorage physicalStorage,
            long maxWalSizeBytes,
            Duration pollInterval
    ) {
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (maxWalSizeBytes < 1) {
            throw new IllegalArgumentException("maxWalSizeBytes must be at least 1");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.maxWalSizeBytes = maxWalSizeBytes;
    }

    @Override
    public void awaitTrigger() throws InterruptedException {
        while (true) {
            if (walSize() >= maxWalSizeBytes) {
                return;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private long walSize() {
        if (!physicalStorage.exists(DefaultWALManager.WAL_FILE)) {
            return 0;
        }
        // Whole-file length is enough: we do not track LSN offsets yet.
        return physicalStorage.read(DefaultWALManager.WAL_FILE).length;
    }
}
