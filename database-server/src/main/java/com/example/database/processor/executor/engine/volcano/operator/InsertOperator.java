package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.analyser.ResolvedInsertValue;
import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;

import java.util.List;
import java.util.Objects;

/**
 * One-shot INSERT: writes the planned values then yields no tuples.
 */
public final class InsertOperator implements VolcanoOperator {

    private final TableStore tableStore;
    private final LockManager lockManager;
    private final String database;
    private final String table;
    private final List<ResolvedInsertValue> values;
    private boolean done;

    public InsertOperator(
            TableStore tableStore,
            LockManager lockManager,
            String database,
            String table,
            List<ResolvedInsertValue> values
    ) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    @Override
    public void open() {
        Object[] row = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) {
            row[i] = values.get(i).value();
        }
        Tuple inserted = tableStore.insert(database, table, row);
        lockManager.lockRow(database, table, inserted.rowId(), LockMode.X);
        done = true;
    }

    @Override
    public Tuple next() {
        return null;
    }

    @Override
    public void close() {
        done = false;
    }
}
