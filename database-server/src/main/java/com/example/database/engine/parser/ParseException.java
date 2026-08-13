package com.example.database.engine.parser;

/**
 * Parse error with the exact 0-based index in the query where parsing failed.
 */
public final class ParseException extends RuntimeException {

    private final int index;

    public ParseException(int index, String detail) {
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
