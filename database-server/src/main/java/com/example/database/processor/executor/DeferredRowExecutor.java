package com.example.database.processor.executor;

import com.example.database.processor.planner.ExecutionPlan;

/**
 * Placeholder for SELECT / INSERT / UPDATE / DELETE until Volcano exists.
 * Analysis and planning already ran; this only acknowledges the plan so the
 * dispatcher does not throw. Not a catalog write and not a row store.
 */
public final class DeferredRowExecutor implements QueryExecutor {

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        return QueryResult.ok();
    }
}
