package com.example.database.storage.checkpoint;

/**
 * Which automatic strategy {@link com.example.database.config.ServerEnvironment} plugs
 * into {@link CheckpointScheduler}. Values map from {@code CHECKPOINT_STRATEGY} in
 * {@code server.env} ({@code timeout} or {@code wal_size}).
 * <p>
 * Manual SQL {@code CHECKPOINT} is always available via the executor and is not a kind here.
 */
public enum CheckpointStrategyKind {
    /** Fire after {@code CHECKPOINT_TIMEOUT_SECONDS}. */
    TIMEOUT,
    /** Fire when {@code wal.log} reaches {@code MAX_WAL_SIZE_BYTES}. */
    WAL_SIZE
}
