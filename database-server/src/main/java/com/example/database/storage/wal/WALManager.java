package com.example.database.storage.wal;

import com.example.database.storage.catalog.CatalogManager;

/**
 * Write-ahead log: durable intent before catalog (and later page) changes.
 * Step 3: catalog DDL records only. Callers {@link #append} then {@link #flush}
 * before applying catalog; {@link #discardPending} on rollback of unflushed work;
 * {@link #replay} on storage start after {@code catalogManager.load()}.
 * <p>
 * {@link #checkpoint} is the durability-lifecycle companion to replay: it does not
 * flush dirty pages (none yet). It records that committed catalog state is on disk and
 * appends a CHECKPOINT fence to the append-only {@code wal.log} (never rewrites history).
 * Caller holds the exclusive catalog lock so checkpoint never runs in the
 * WAL-flush → catalog-persist gap.
 */
public interface WALManager {

    /** Queue a record for the current transaction (not durable until {@link #flush}). */
    void append(WalRecord record);

    /**
     * Write pending records to {@code wal.log} and force them to disk.
     * Makes intent durable so crash recovery can replay.
     */
    void flush();

    /** Drop unflushed records for this thread (transaction rollback before flush). */
    void discardPending();

    /**
     * Re-apply committed WAL records that are missing from the catalog (idempotent).
     * Call after {@link CatalogManager#load()}. Applies only records after the last
     * {@code CHECKPOINT} line; merges {@code wal.checkpoint} maxTxnId. Older lines remain
     * on disk because the log is append-only.
     *
     * @return highest {@code txnId} seen in the log or checkpoint (0 if none)
     */
    int replay(CatalogManager catalogManager);

    /**
     * Durable-only barrier: write {@code wal.checkpoint} with maxTxnId, then
     * <strong>append</strong> a {@code CHECKPOINT} line to {@code wal.log} (never replace
     * or empty the log). Safe only when catalog files already reflect every committed op
     * through that high-water mark. Caller must hold exclusive catalog lock.
     *
     * @return maxTxnId stored in the checkpoint file
     */
    int checkpoint();
}
