package com.example.database.processor.analyser;

/**
 * Semantic analysis failure (unknown table, duplicate column, …).
 * Unlike lex/parse errors there is no character index — the whole statement is invalid.
 */
public final class AnalysisException extends RuntimeException {

    public AnalysisException(String detail) {
        super(detail);
    }

    public String toResponse() {
        return "ERROR: " + getMessage();
    }
}
