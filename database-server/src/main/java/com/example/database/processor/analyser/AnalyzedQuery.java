package com.example.database.processor.analyser;

/**
 * Result of semantic analysis. DDL and DML/DQL become analyzed forms;
 * anything not yet handled stays {@link UnresolvedQuery}.
 */
public sealed interface AnalyzedQuery permits
        AnalyzedCreateTable,
        AnalyzedCreateDatabase,
        AnalyzedDropTable,
        AnalyzedDropDatabase,
        AnalyzedAddColumn,
        AnalyzedDropColumn,
        AnalyzedCreateIndex,
        AnalyzedDropIndex,
        AnalyzedBegin,
        AnalyzedCommit,
        AnalyzedRollback,
        AnalyzedCheckpoint,
        AnalyzedDescribeTable,
        AnalyzedShowDatabases,
        AnalyzedShowTables,
        AnalyzedSelect,
        AnalyzedInsert,
        AnalyzedUpdate,
        AnalyzedDelete,
        UnresolvedQuery {
}
