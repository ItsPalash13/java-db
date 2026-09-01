package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;

import java.util.Iterator;
import java.util.Objects;

/**
 * Full heap scan. Locks each row S while the tuple is current (released on next/close).
 */
public final class SeqScan implements VolcanoOperator {

    private final TableStore tableStore;
    private final LockManager lockManager;
    private final String database;
    private final String table;
    private Iterator<Tuple> iterator;
    private Tuple lockedTuple;

    public SeqScan(TableStore tableStore, String database, String table) {
        this(tableStore, null, database, table);
    }

    public SeqScan(TableStore tableStore, LockManager lockManager, String database, String table) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = lockManager;
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public void open() {
        iterator = tableStore.scan(database, table);
    }

    @Override
    public Tuple next() {
        unlockCurrentRow();
        if (iterator == null || !iterator.hasNext()) {
            return null;
        }
        Tuple tuple = iterator.next();
        if (lockManager != null) {
            lockManager.lockRow(database, table, tuple.rowId(), LockMode.S);
        }
        lockedTuple = tuple;
        return tuple;
    }

    @Override
    public void close() {
        unlockCurrentRow();
        iterator = null;
    }

    private void unlockCurrentRow() {
        if (lockedTuple != null && lockManager != null) {
            lockManager.unlockRow(database, table, lockedTuple.rowId(), LockMode.S);
            lockedTuple = null;
        } else if (lockedTuple != null) {
            lockedTuple = null;
        }
    }
}
