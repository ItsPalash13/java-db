package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;

import java.util.Objects;

/**
 * Deletes each child tuple from TableStore. Yields nothing to the parent.
 */
public final class DeleteOperator implements VolcanoOperator {

    private final VolcanoOperator child;
    private final TableStore tableStore;
    private final LockManager lockManager;
    private final String database;
    private final String table;

    public DeleteOperator(
            VolcanoOperator child,
            TableStore tableStore,
            LockManager lockManager,
            String database,
            String table
    ) {
        this.child = Objects.requireNonNull(child, "child");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        while (true) {
            Tuple tuple = child.next();
            if (tuple == null) {
                return null;
            }
            lockManager.lockRow(database, table, tuple.rowId(), LockMode.X);
            try {
                tableStore.delete(database, table, tuple.rowId());
            } finally {
                lockManager.unlockRow(database, table, tuple.rowId(), LockMode.X);
            }
        }
    }

    @Override
    public void close() {
        child.close();
    }
}
