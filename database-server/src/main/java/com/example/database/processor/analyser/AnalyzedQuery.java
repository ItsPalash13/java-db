package com.example.database.processor.analyser;

/**
 * Result of semantic analysis. CREATE/DROP TABLE and CREATE/DROP DATABASE become
 * analyzed forms; other statement types stay {@link UnresolvedQuery} until their own phases.
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
        UnresolvedQuery {
}
