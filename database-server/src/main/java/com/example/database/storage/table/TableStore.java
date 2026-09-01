package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;

import java.util.Iterator;

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

    /** Captures all heap rows for rollback of an explicit transaction. */
    TableSnapshot snapshot();

    /** Restores heap rows from a {@link #snapshot()} taken at {@code BEGIN}. */
    void restoreSnapshot(TableSnapshot snapshot);
}
