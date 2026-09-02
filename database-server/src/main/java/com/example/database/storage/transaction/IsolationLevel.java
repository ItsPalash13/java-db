package com.example.database.storage.transaction;

/**
 * SQL isolation level for explicit and implicit transactions.
 * {@link #READ_COMMITTED} releases shared locks at statement end; write locks until COMMIT/ABORT.
 */
public enum IsolationLevel {
    READ_COMMITTED,
    /** Not implemented yet — shared locks would be held until COMMIT. */
    REPEATABLE_READ
}
