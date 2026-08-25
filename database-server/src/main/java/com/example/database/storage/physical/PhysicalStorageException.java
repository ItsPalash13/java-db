package com.example.database.storage.physical;

/**
 * Storage-layer I/O failure. Wraps filesystem errors so callers do not catch {@code IOException}.
 */
public final class PhysicalStorageException extends RuntimeException {

    public PhysicalStorageException(String detail) {
        super(detail);
    }

    public PhysicalStorageException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
