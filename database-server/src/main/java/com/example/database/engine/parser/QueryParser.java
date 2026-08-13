package com.example.database.engine.parser;

import com.example.database.engine.lexer.Token;

import java.util.List;

/**
 * Turns a list of {@link Token}s into an {@link AstNode}.
 */
public interface QueryParser {

    AstNode parse(List<Token> tokens);
}
