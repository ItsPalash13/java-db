package com.example.database.processor.planner;

/**
 * What the executor will run. DDL is a command plan, not a Volcano operator tree.
 */
public sealed interface ExecutionPlan permits
        CreateTablePlan,
        CreateDatabasePlan,
        DropDatabasePlan,
        UnresolvedPlan {

    QueryType queryType();
}
