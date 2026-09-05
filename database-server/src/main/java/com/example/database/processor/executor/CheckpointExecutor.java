package com.example.database.processor.executor;

import com.example.database.processor.planner.CheckpointPlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;

import java.util.Objects;

/**
 * Manual {@code CHECKPOINT} SQL path — administrator force, not a {@code CheckpointStrategy}.
 * <p>
 * Under ENGINE X: flush WAL → {@link BufferPool#flushAll()} (WAL-before-data per dirty
 * frame) → exclusive catalog + {@link WALManager#checkpoint()} fence.
 * {@code flushAll} also clears dirty bits so no-steal clock eviction can reuse frames —
 * important when fat index keys ({@code INDEX_KEY_PADDING_BYTES}) create many dirty pages.
 * Refuses inside an explicit transaction or while any other connection has an open
 * {@code BEGIN}.
 */
public final class CheckpointExecutor implements QueryExecutor {

    private final LockManager lockManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;
    private final BufferPool bufferPool;

    public CheckpointExecutor(
            LockManager lockManager,
            WALManager walManager,
            TransactionManager transactionManager,
            BufferPool bufferPool
    ) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!(plan instanceof CheckpointPlan)) {
            throw new ExecutionException("CheckpointExecutor cannot execute " + plan.queryType());
        }
        if (transactionManager.inExplicitTransaction()) {
            throw new ExecutionException("CHECKPOINT is not allowed inside an explicit transaction");
        }
        if (transactionManager.activeExplicitSessionCount() > 0) {
            throw new ExecutionException(
                    "CHECKPOINT is not allowed while other explicit transactions are active"
            );
        }
        try {
            lockManager.runWithEngineX(() -> lockManager.runExclusiveCatalog(() -> {
                // 1) Durable log  2) dirty pages (each flushUpTo)  3) recovery fence
                walManager.flush();
                bufferPool.flushAll();
                walManager.checkpoint();
            }));
            return QueryResult.ok();
        } catch (RuntimeException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
    }
}
