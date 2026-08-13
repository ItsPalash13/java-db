package com.example.database.engine.lexer;

import java.util.List;

/**
 * Turns a query string into a list of {@link Token}s from {@link TokenCatalog}.
 *
 * @throws LexException if scanning fails; {@link LexException#index()} is the exact place
 */
public interface QueryLexer {

    List<Token> tokenize(String query);
}
