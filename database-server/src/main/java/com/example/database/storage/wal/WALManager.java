package com.example.database.storage.wal;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.table.TableStore;

/**
 * Write-ahead log: durable intent before catalog and heap/index mutations.
 * Callers {@link #append} / {@link #appendReturningLsn} then {@link #flush}
 * before relying on crash recovery; {@link #discardPending} on rollback of
 * unflushed work; {@link #replay} then {@link #redoDml} on storage start.
 * <p>
 * {@link #checkpoint} records the recovery fence in {@code wal.log}. Callers
 * must flush the buffer pool (dirty pages) under ENGINE X <em>before</em>
 * invoking checkpoint so page bytes on disk are covered by the fence.
 */
public interface WALManager {

    /** Queue a record for the current transaction (not durable until {@link #flush}). */
    void append(WalRecord record);

    /**
     * Assign a monotonic LSN, queue the record, and return that LSN for stamping
     * on the heap page. LSN is a counter (not a byte offset); restart advances
     * past the max {@code lsn} already present in {@code wal.log}.
     */
    long appendReturningLsn(WalRecord record);

    /**
     * Write pending records to {@code wal.log} and force them to disk.
     * Makes intent durable so crash recovery can replay.
     */
    void flush();

    /**
     * Ensure every record with LSN ≤ {@code lsn} is on disk. Flushes this thread's
     * pending stream when DML shares it with COMMIT (teaching simplification).
     */
    void flushUpTo(long lsn);

    /** Drop unflushed records for this thread (transaction rollback before flush). */
    void discardPending();

    /**
     * Re-apply committed <em>catalog</em> WAL records missing from the catalog.
     * Skips DML ops (those are {@link #redoDml}). Call after {@code catalogManager.load()}.
     *
     * @return highest {@code txnId} seen in the log or checkpoint (0 if none)
     */
    int replay(CatalogManager catalogManager);

    /**
     * Re-apply committed logical DML / index ops after the last CHECKPOINT fence.
     * Idempotent. Does not redo uncommitted groups. Caller should suppress heap WAL
     * logging and index side-effects while this runs.
     */
    void redoDml(TableStore tableStore, IndexStore indexStore);

    /**
     * Durable barrier: write {@code wal.checkpoint} with maxTxnId, then append a
     * {@code CHECKPOINT} line. Caller must already have flushed WAL and dirty pages.
     *
     * @return maxTxnId stored in the checkpoint file
     */
    int checkpoint();
}
