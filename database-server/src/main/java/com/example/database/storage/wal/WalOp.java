package com.example.database.storage.wal;

/**
 * Catalog DDL operations recorded in the write-ahead log.
 * DML page records will add more kinds later.
 */
public enum WalOp {
    CREATE_DATABASE,
    DROP_DATABASE,
    CREATE_TABLE,
    DROP_TABLE,
    ADD_COLUMN,
    DROP_COLUMN,
    CREATE_INDEX,
    DROP_INDEX,
    /** Marks a transaction committed; replay applies buffered DDL for this {@code txnId}. */
    COMMIT,
    /**
     * Recovery barrier appended to {@code wal.log} after checkpoint. Not catalog redo —
     * {@code txnId} carries the durable high-water mark. History before this line stays
     * on disk; replay skips applying it.
     */
    CHECKPOINT
}
