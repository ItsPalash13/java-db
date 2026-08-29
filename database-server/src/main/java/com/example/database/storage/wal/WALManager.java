package com.example.database.storage.wal;

import com.example.database.storage.catalog.CatalogManager;

/**
 * Write-ahead log: durable intent before catalog (and later page) changes.
 * Step 3: catalog DDL records only. Callers {@link #append} then {@link #flush}
 * before applying catalog; {@link #discardPending} on rollback of unflushed work;
 * {@link #replay} on storage start after {@code catalogManager.load()}.
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
     * Call after {@link CatalogManager#load()}.
     *
     * @return highest {@code txnId} seen in the log (0 if empty or legacy-only)
     */
    int replay(CatalogManager catalogManager);
}
