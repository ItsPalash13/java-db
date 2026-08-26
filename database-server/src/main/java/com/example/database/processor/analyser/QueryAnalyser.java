package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;

/**
 * Validates a parsed AST against catalog metadata. Read-only for DDL in Phase 1 —
 * {@link com.example.database.storage.catalog.CatalogManager#createTable} runs in the executor.
 */
public interface QueryAnalyser {

    /**
     * @throws AnalysisException when the statement is syntactically parsed but semantically invalid
     */
    AnalyzedQuery analyse(AstNode ast);
}
