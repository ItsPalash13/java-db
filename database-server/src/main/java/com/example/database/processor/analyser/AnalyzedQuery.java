package com.example.database.processor.analyser;

/**
 * Result of semantic analysis. {@link CreateTableQuery} becomes {@link AnalyzedCreateTable};
 * other statement types stay {@link UnresolvedQuery} until their own analysis phases.
 */
public sealed interface AnalyzedQuery permits AnalyzedCreateTable, UnresolvedQuery {
}
