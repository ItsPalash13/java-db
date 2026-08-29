package com.example.database.processor.planner;

public record DescribeTablePlan(String database, String table) implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.DESCRIBE_TABLE;
    }
}
