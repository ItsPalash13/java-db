package com.example.database.processor.executor;

import com.example.database.processor.planner.ExecutionPlan;

/**
 * Runs one {@link ExecutionPlan}. DDL uses {@link CommandExecutor};
 * DML/DQL uses {@link DeferredRowExecutor} until Volcano exists.
 */
public interface QueryExecutor {

    QueryResult execute(ExecutionPlan plan);
}
