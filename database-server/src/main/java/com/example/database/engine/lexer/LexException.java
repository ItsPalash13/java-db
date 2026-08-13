package com.example.database.engine.lexer;

/**
 * Lexical error with the exact 0-based index in the query where scanning failed.
 */
public final class LexException extends RuntimeException {

    private final int index;

    public LexException(int index, String detail) {
        super(detail);
        this.index = index;
    }

    /** 0-based character index in the original query. */
    public int index() {
        return index;
    }

    /** Client-facing message including the exact place the error happened. */
    public String toResponse() {
        return "ERROR at index " + index + ": " + getMessage();
    }
}
