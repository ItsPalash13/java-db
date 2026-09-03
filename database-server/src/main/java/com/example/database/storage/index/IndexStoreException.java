package com.example.database.storage.index;

/**
 * Index store failures surfaced to DDL/DML callers.
 */
public final class IndexStoreException extends RuntimeException {

    public IndexStoreException(String message) {
        super(message);
    }

    public IndexStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
