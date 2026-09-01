package com.example.database.storage.lock;

/**
 * Lock wait timed out or was interrupted. Mapped to a client ERROR response.
 */
public class LockException extends RuntimeException {

    public LockException(String message) {
        super(message);
    }

    public LockException(String message, Throwable cause) {
        super(message, cause);
    }
}
