package com.example.database.storage.checkpoint;

import java.time.Duration;
import java.util.Objects;

/**
 * Time-based trigger: fire after {@code checkpoint_timeout} since the previous
 * {@link #awaitTrigger()} returned (Postgres-style {@code checkpoint_timeout}).
 * Does not look at WAL size — pair with {@link WalSizeCheckpointStrategy} by changing
 * {@code CHECKPOINT_STRATEGY} in {@code server.env}, not by combining both in one class.
 */
public final class TimeoutCheckpointStrategy implements CheckpointStrategy {

    private final Duration timeout;

    public TimeoutCheckpointStrategy(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("checkpoint timeout must be positive");
        }
    }

    @Override
    public void awaitTrigger() throws InterruptedException {
        // Thread.sleep is interruptible; CheckpointScheduler.stop() interrupts this worker.
        Thread.sleep(timeout.toMillis());
    }
}
