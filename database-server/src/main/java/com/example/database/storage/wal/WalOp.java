package com.example.database.storage.wal;

/**
 * Operations recorded in the write-ahead log.
 * Catalog DDL plus logical DML / index redo (Phase 6).
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
    /** Logical heap insert — redo applies row if missing. */
    INSERT_ROW,
    /** Logical heap update — redo replaces values for {@code rowId}. */
    UPDATE_ROW,
    /** Logical heap delete — redo removes {@code rowId} if present. */
    DELETE_ROW,
    /** Logical secondary-index insert (key → Rid). */
    INDEX_INSERT,
    /** Logical secondary-index delete (key → Rid). */
    INDEX_DELETE,
    /** Marks a transaction committed; replay applies buffered records for this {@code txnId}. */
    COMMIT,
    /**
     * Recovery barrier appended to {@code wal.log} after checkpoint. Not catalog redo —
     * {@code txnId} carries the durable high-water mark. History before this line stays
     * on disk; replay skips applying it.
     */
    CHECKPOINT;

    /** Heap or index logical DML — not applied by catalog {@code replay}. */
    public boolean isDml() {
        return this == INSERT_ROW
                || this == UPDATE_ROW
                || this == DELETE_ROW
                || this == INDEX_INSERT
                || this == INDEX_DELETE;
    }
}
