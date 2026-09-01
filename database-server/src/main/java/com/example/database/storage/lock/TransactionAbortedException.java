package com.example.database.storage.lock;

/**
 * Victim of Wait-Die, Wound-Wait, or deadlock resolution. Caller should rollback and
 * return ERROR; client may retry the statement.
 */
public final class TransactionAbortedException extends LockException {

    public TransactionAbortedException(String message) {
        super(message);
    }
}
