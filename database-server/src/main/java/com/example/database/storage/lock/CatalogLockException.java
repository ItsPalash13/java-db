package com.example.database.storage.lock;

/**
 * Catalog lock could not be acquired within the configured wait (like lock_timeout).
 * Not a parse/analysis error — callers map this to a client ERROR response.
 */
public final class CatalogLockException extends RuntimeException {

    public CatalogLockException(String message) {
        super(message);
    }

    public CatalogLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
