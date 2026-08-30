package com.example.database.processor.planner;

/**
 * What the executor will run. DDL is a command plan, not a Volcano operator tree.
 */
public sealed interface ExecutionPlan permits
        CreateTablePlan,
        CreateDatabasePlan,
        DropTablePlan,
        DropDatabasePlan,
        AddColumnPlan,
        DropColumnPlan,
        CreateIndexPlan,
        DropIndexPlan,
        BeginPlan,
        CommitPlan,
        RollbackPlan,
        CheckpointPlan,
        DescribeTablePlan,
        ShowDatabasesPlan,
        ShowTablesPlan,
        UnresolvedPlan {

    QueryType queryType();
}
