package com.example.database.processor.executor;

/**
 * Failed plan execution (missing executor, catalog conflict, …).
 * Same response shape as analysis: no character index.
 */
public final class ExecutionException extends RuntimeException {

    public ExecutionException(String detail) {
        super(detail);
    }

    public ExecutionException(String detail, Throwable cause) {
        super(detail, cause);
    }

    public String toResponse() {
        return "ERROR: " + getMessage();
    }
}
