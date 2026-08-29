package com.example.database.processor.planner;

public record ShowDatabasesPlan() implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.SHOW_DATABASES;
    }
}
