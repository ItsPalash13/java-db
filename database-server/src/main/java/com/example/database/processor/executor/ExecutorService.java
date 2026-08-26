package com.example.database.processor.executor;

import com.example.database.processor.planner.ExecutionPlan;

import java.util.Objects;

/**
 * Orchestrates execution: look up {@link QueryExecutor} and call it.
 * Not {@link java.util.concurrent.ExecutorService} — no thread pool, no scheduling.
 */
public final class ExecutorService {

    private final ExecutorRegistry registry;

    public ExecutorService(ExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return registry.get(plan.queryType()).execute(plan);
    }
}
