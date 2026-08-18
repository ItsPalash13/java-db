package com.example.database.processor.lexer;

import java.util.Objects;

/**
 * One lexical token: a {@link TokenCatalog} kind, lexeme text, and start index in the query.
 */
public final class Token {

    private final TokenCatalog kind;
    private final String lexeme;
    private final int index;

    public Token(TokenCatalog kind, String lexeme, int index) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.lexeme = Objects.requireNonNull(lexeme, "lexeme");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        this.index = index;
    }

    public TokenCatalog kind() {
        return kind;
    }

    public String lexeme() {
        return lexeme;
    }

    /** 0-based start index of this token in the original query. */
    public int index() {
        return index;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Token that)) {
            return false;
        }
        return kind == that.kind && lexeme.equals(that.lexeme) && index == that.index;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, lexeme, index);
    }

    @Override
    public String toString() {
        return kind + "(" + lexeme + ")@" + index;
    }
}
