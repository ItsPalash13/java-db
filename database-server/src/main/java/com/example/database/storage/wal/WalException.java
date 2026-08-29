package com.example.database.storage.wal;

/**
 * WAL I/O or parse failure. Catalog conflicts during replay are skipped when idempotent.
 */
public final class WalException extends RuntimeException {

    public WalException(String message) {
        super(message);
    }

    public WalException(String message, Throwable cause) {
        super(message, cause);
    }
}
