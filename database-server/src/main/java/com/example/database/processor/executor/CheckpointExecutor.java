package com.example.database.processor.executor;

import com.example.database.processor.planner.CheckpointPlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;

import java.util.Objects;

/**
 * Manual {@code CHECKPOINT} SQL path — administrator force, not a {@code CheckpointStrategy}.
 * <p>
 * Runs the same durable-only {@link WALManager#checkpoint()} as the background scheduler.
 * Refuses inside an explicit transaction or while any other connection has an open
 * {@code BEGIN} (deferred catalog / uncommitted WAL must not be truncated).
 */
public final class CheckpointExecutor implements QueryExecutor {

    private final LockManager lockManager;
    private final WALManager walManager;
    private final TransactionManager transactionManager;

    public CheckpointExecutor(
            LockManager lockManager,
            WALManager walManager,
            TransactionManager transactionManager
    ) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
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
            lockManager.runExclusiveCatalog(walManager::checkpoint);
            return QueryResult.ok();
        } catch (RuntimeException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
    }
}
