package com.example.database.processor.executor.engine.batch;

import com.example.database.processor.executor.ExecutionException;
import com.example.database.processor.executor.QueryExecutor;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.planner.ExecutionPlan;

/**
 * Placeholder for a future batch materializing engine.
 * Not registered in {@code DefaultQueryProcessor} — Volcano is plugged in today.
 */
public final class BatchExecutor implements QueryExecutor {

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        throw new ExecutionException("BatchExecutor is not implemented");
    }
}
