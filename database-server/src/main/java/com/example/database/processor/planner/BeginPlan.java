package com.example.database.processor.planner;

public record BeginPlan() implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.BEGIN;
    }
}
