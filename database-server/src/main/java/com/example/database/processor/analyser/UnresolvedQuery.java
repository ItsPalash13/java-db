package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;

import java.util.Objects;

/**
 * Parsed AST accepted but not semantically resolved yet.
 * SELECT / INSERT / UPDATE / DELETE are no longer this type.
 */
public final class UnresolvedQuery implements AnalyzedQuery {

    private final AstNode source;

    public UnresolvedQuery(AstNode source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public AstNode source() {
        return source;
    }

    @Override
    public String toString() {
        return "UnresolvedQuery{source=" + source + "}";
    }
}
