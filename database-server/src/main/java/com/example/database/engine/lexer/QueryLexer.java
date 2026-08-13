package com.example.database.engine.lexer;

import java.util.List;

/**
 * Turns a query string into a list of {@link Token}s from {@link TokenCatalog}.
 */
public interface QueryLexer {

    List<Token> tokenize(String query);
}
