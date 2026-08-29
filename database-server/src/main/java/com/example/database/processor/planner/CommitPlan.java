package com.example.database.processor.planner;

public record CommitPlan() implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.COMMIT;
    }
}
