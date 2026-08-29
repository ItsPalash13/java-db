package com.example.database.processor.executor;

import com.example.database.processor.planner.BeginPlan;
import com.example.database.processor.planner.CommitPlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.processor.planner.RollbackPlan;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.transaction.TransactionManager;

import java.util.Objects;

/**
 * Client-visible {@code BEGIN} / {@code COMMIT} / {@code ROLLBACK}. Does not use
 * {@link CommandExecutor}'s implicit {@code runInTransaction} wrapper.
 */
public final class TransactionControlExecutor implements QueryExecutor {

    private final TransactionManager transactionManager;
    private final LockManager lockManager;
    private final CatalogManager catalogManager;

    public TransactionControlExecutor(
            TransactionManager transactionManager,
            LockManager lockManager,
            CatalogManager catalogManager
    ) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try {
            if (plan instanceof BeginPlan) {
                transactionManager.beginExplicit(lockManager, catalogManager);
                return QueryResult.ok();
            }
            if (plan instanceof CommitPlan) {
                transactionManager.commitExplicit(lockManager, catalogManager);
                return QueryResult.ok();
            }
            if (plan instanceof RollbackPlan) {
                transactionManager.rollbackExplicit(lockManager, catalogManager);
                return QueryResult.ok();
            }
        } catch (IllegalStateException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        throw new ExecutionException("TransactionControlExecutor cannot execute " + plan.queryType());
    }
}
