package com.example.database.engine.lexer;

import java.util.List;

/**
 * Default query lexer stub. Returns {@link TokenCatalog#EOF} only until real tokenization lands.
 */
public final class DefaultQueryLexer implements QueryLexer {

    @Override
    public List<Token> tokenize(String query) {
        return List.of(new Token(TokenCatalog.EOF, ""));
    }
}
