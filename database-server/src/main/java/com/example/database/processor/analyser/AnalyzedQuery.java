package com.example.database.processor.analyser;

/**
 * Result of semantic analysis. CREATE TABLE / CREATE DATABASE / DROP DATABASE become
 * analyzed forms; other statement types stay {@link UnresolvedQuery} until their own phases.
 */
public sealed interface AnalyzedQuery permits
        AnalyzedCreateTable,
        AnalyzedCreateDatabase,
        AnalyzedDropDatabase,
        UnresolvedQuery {
}
