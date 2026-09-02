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
        if (iterator == null) {
            return null;
        }
        while (iterator.hasNext()) {
            Tuple snapshotRow = iterator.next();
            long rowId = snapshotRow.rowId();
            if (lockManager != null) {
                lockManager.lockRow(database, table, rowId, LockMode.S);
                // Snapshot from open() can include an uncommitted version; re-read only after
                // S-lock is granted so rollback/commit while we blocked is visible correctly.
                Tuple current = tableStore.findByRowId(database, table, rowId).orElse(null);
                if (current == null) {
                    lockManager.unlockRow(database, table, rowId, LockMode.S);
                    continue;
                }
                lockedTuple = current;
                return current;
            }
            lockedTuple = snapshotRow;
            return snapshotRow;
        }
        return null;
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
