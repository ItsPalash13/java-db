package com.example.database.storage.page;

/**
 * Thrown when page bytes are corrupt, a codec gets the wrong types/values,
 * or a heap page cannot fit another row. Not a SQL error — callers map it.
 */
public final class PageLayoutException extends RuntimeException {

    public PageLayoutException(String message) {
        super(message);
    }

    public PageLayoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
