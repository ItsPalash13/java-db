package com.example.database.processor.executor.engine.vectorized;

import com.example.database.processor.executor.ExecutionException;
import com.example.database.processor.executor.QueryExecutor;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.planner.ExecutionPlan;

/**
 * Placeholder for a future vectorized / columnar batch engine.
 * Not registered in {@code DefaultQueryProcessor} — Volcano is plugged in today.
 */
public final class VectorizedExecutor implements QueryExecutor {

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        throw new ExecutionException("VectorizedExecutor is not implemented");
    }
}
