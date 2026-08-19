package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;

/**
 * Validates a parsed AST against catalog metadata (tables, columns, indexes).
 * Stub: always succeeds until {@code CatalogManager} is wired in.
 */
public interface QueryAnalyser {

    /**
     * @return {@code true} if the AST is valid; {@code false} or an error response later on failure
     */
    boolean analyse(AstNode ast);
}
