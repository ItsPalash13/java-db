package com.example.database.processor.planner;

/**
 * What the executor will run. DDL is a command plan; DML/DQL is a row plan (Volcano later).
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
        SelectPlan,
        InsertPlan,
        UpdatePlan,
        DeletePlan,
        UnresolvedPlan {

    QueryType queryType();
}
