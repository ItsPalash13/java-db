package com.example.database.engine.lexer;

import java.util.Objects;

/**
 * One lexical token: a {@link TokenCatalog} kind plus its lexeme text.
 */
public final class Token {

    private final TokenCatalog kind;
    private final String lexeme;

    public Token(TokenCatalog kind, String lexeme) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.lexeme = Objects.requireNonNull(lexeme, "lexeme");
    }

    public TokenCatalog kind() {
        return kind;
    }

    public String lexeme() {
        return lexeme;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Token that)) {
            return false;
        }
        return kind == that.kind && lexeme.equals(that.lexeme);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, lexeme);
    }

    @Override
    public String toString() {
        return kind + "(" + lexeme + ")";
    }
}
