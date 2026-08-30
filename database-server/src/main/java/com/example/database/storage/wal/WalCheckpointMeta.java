package com.example.database.storage.wal;

/**
 * Durable recovery cursor stored in {@code wal.checkpoint}.
 * <p>
 * Separated from {@code wal.log} on purpose: checkpoint truncates the log, but restart
 * still needs {@code maxTxnId} so {@code TransactionManager} does not reissue ids.
 * This is not catalog metadata and not redo — only a fence for recovery.
 */
record WalCheckpointMeta(int maxTxnId) {

    WalCheckpointMeta {
        if (maxTxnId < 0) {
            throw new IllegalArgumentException("maxTxnId must be >= 0");
        }
    }
}
