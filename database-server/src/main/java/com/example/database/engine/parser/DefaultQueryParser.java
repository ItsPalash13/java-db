package com.example.database.engine.parser;

import com.example.database.engine.lexer.Token;

import java.util.List;

/**
 * Default query parser stub. Returns an empty {@link AstNode} until real parsing lands.
 */
public final class DefaultQueryParser implements QueryParser {

    @Override
    public AstNode parse(List<Token> tokens) {
        return new AstNode();
    }
}
