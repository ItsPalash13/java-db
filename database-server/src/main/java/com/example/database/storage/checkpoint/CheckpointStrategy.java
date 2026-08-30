package com.example.database.storage.checkpoint;

/**
 * Decides <em>when</em> the background {@link CheckpointScheduler} runs a durable
 * checkpoint. The action itself lives on {@link com.example.database.storage.wal.WALManager#checkpoint()}.
 * <p>
 * Strategy pattern so operators plug exactly one automatic policy (timeout or WAL size)
 * at {@code StorageEngine} construction via {@code server.env}. Manual {@code CHECKPOINT}
 * SQL does <strong>not</strong> implement this interface — it goes through
 * {@code CheckpointExecutor} and calls the same WAL action immediately.
 */
public interface CheckpointStrategy {

    /**
     * Blocks until this policy says a checkpoint should run.
     * Implementations must be interruptible so {@link CheckpointScheduler#stop()} can
     * wake a sleeping or polling worker without waiting for the next natural trigger.
     *
     * @throws InterruptedException if the scheduler is stopping
     */
    void awaitTrigger() throws InterruptedException;
}
