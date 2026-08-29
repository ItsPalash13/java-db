package com.example.database.processor.planner;

public record RollbackPlan() implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.ROLLBACK;
    }
}
