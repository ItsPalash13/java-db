package com.example.database.processor.executor;

/**
 * Client-facing outcome of running a plan. DDL is a status string, not a row stream.
 */
public final class QueryResult {

    private final String message;

    public static QueryResult ok() {
        return new QueryResult("OK");
    }

    private QueryResult(String message) {
        this.message = message;
    }

    public String toResponse() {
        return message;
    }

    @Override
    public String toString() {
        return message;
    }
}
