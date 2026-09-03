package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.page.Rid;

import java.util.Iterator;
import java.util.Optional;

/**
 * Owns actual table data (rows for user tables). Does not own schema —
 * that is {@code CatalogManager}. Temporary {@link InMemoryTableStore} holds
 * RAM heaps; a later file store will go through BufferPool / pages.
 */
public interface TableStore {

    /**
     * Appends a row and assigns a fresh {@code rowId}. {@code values} length must
     * match the table's column count (caller is Volcano after analysis).
     */
    Tuple insert(String database, String table, Object[] values);

    /** Full heap scan in insertion order. Empty if the table has never been written. */
    Iterator<Tuple> scan(String database, String table);

    /** Replaces values for an existing {@code rowId}; no-op if the id is unknown. */
    void update(String database, String table, long rowId, Object[] values);

    /** Removes the row with {@code rowId}; no-op if missing. */
    void delete(String database, String table, long rowId);

    /**
     * Drops the heap for one table. Required after DROP TABLE so a recreate
     * does not see stale RAM rows (catalog and store would otherwise diverge).
     */
    void dropTable(String database, String table);

    /** Drops every heap under {@code database}. Called after DROP DATABASE. */
    void dropDatabase(String database);

    /**
     * Prepares on-disk heap storage when a table is created. File-backed stores create an
     * empty {@code .ibd}; in-memory stores ignore this.
     */
    default void prepareTable(String database, String table) {
        // no-op for InMemoryTableStore
    }

    /** Captures all heap rows for rollback of an explicit transaction. */
    TableSnapshot snapshot();

    /** Restores heap rows from a {@link #snapshot()} taken at {@code BEGIN}. */
    void restoreSnapshot(TableSnapshot snapshot);

    /** Returns one row by id for undo capture before UPDATE/DELETE. */
    Optional<Tuple> findByRowId(String database, String table, long rowId);

    /**
     * Returns the heap address for a logical row id. Used by index maintenance and index scans.
     * File-backed stores resolve through RidMap; in-memory stores return empty.
     */
    default Optional<Rid> findRid(String database, String table, long rowId) {
        return Optional.empty();
    }

    /**
     * Reads a live row at a heap address without going through RidMap.
     * File-backed stores pin/latch the page; in-memory stores return empty.
     */
    default Optional<Tuple> findByRid(String database, String table, Rid rid) {
        return Optional.empty();
    }

    /**
     * Re-inserts a row with a fixed {@code rowId} during undo of DELETE.
     * Not used for normal INSERT — {@link #insert} assigns ids.
     */
    void restoreRow(String database, String table, Tuple tuple);
}
