package com.example.database.processor.executor;

import com.example.database.processor.planner.ExecutionPlan;

import java.util.Objects;

/**
 * Looks up a {@link QueryExecutor} and runs the plan on the caller thread.
 * Not a thread pool: network workers already own the request from receive through execute.
 */
public final class QueryDispatcher {

    private final ExecutorRegistry registry;

    public QueryDispatcher(ExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return registry.get(plan.queryType()).execute(plan);
    }
}
