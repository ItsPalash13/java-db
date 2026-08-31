package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.analyser.ResolvedInsertValue;
import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.table.TableStore;

import java.util.List;
import java.util.Objects;

/**
 * One-shot INSERT: writes the planned values then yields no tuples.
 * Not a scan — VolcanoExecutor opens and drains next() once to trigger the write.
 */
public final class InsertOperator implements VolcanoOperator {

    private final TableStore tableStore;
    private final String database;
    private final String table;
    private final List<ResolvedInsertValue> values;
    private boolean done;

    public InsertOperator(
            TableStore tableStore,
            String database,
            String table,
            List<ResolvedInsertValue> values
    ) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    @Override
    public void open() {
        Object[] row = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) {
            // AnalyzedInsert is already catalog column order; columnId == i + 1.
            row[i] = values.get(i).value();
        }
        tableStore.insert(database, table, row);
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
