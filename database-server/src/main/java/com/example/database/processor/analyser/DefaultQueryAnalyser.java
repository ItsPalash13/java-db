package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;

/**
 * Default analyser stub: returns {@code true} for every AST.
 * Will use {@code CatalogManager} to check that referenced databases, tables, and columns exist.
 */
public final class DefaultQueryAnalyser implements QueryAnalyser {

    @Override
    public boolean analyse(AstNode ast) {
        return true;
    }
}
