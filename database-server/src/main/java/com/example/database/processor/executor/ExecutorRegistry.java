package com.example.database.processor.executor;

import com.example.database.processor.planner.QueryType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lookup only: {@link QueryType} → {@link QueryExecutor}. Does not run plans.
 */
public final class ExecutorRegistry {

    private final Map<QueryType, QueryExecutor> executors = new EnumMap<>(QueryType.class);

    public void register(QueryType type, QueryExecutor executor) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(executor, "executor");
        QueryExecutor previous = executors.putIfAbsent(type, executor);
        if (previous != null) {
            throw new IllegalStateException("executor already registered for " + type);
        }
    }

    public QueryExecutor get(QueryType type) {
        Objects.requireNonNull(type, "type");
        QueryExecutor executor = executors.get(type);
        if (executor == null) {
            throw new ExecutionException("no executor registered for " + type);
        }
        return executor;
    }
}
