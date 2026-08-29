package com.example.database.processor.planner;

public record ShowTablesPlan(String database) implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.SHOW_TABLES;
    }
}
