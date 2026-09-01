package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.analyser.ResolvedAssignment;
import com.example.database.processor.executor.engine.volcano.ExpressionEvaluator;
import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;

import java.util.List;
import java.util.Objects;

/**
 * Applies SET assignments to each child tuple and writes the new row through TableStore.
 * Yields nothing to the parent — side-effecting operator.
 */
public final class UpdateOperator implements VolcanoOperator {

    private final VolcanoOperator child;
    private final TableStore tableStore;
    private final LockManager lockManager;
    private final String database;
    private final String table;
    private final List<ResolvedAssignment> assignments;
    private final ExpressionEvaluator evaluator;
    private final int columnCount;

    public UpdateOperator(
            VolcanoOperator child,
            TableStore tableStore,
            LockManager lockManager,
            String database,
            String table,
            List<ResolvedAssignment> assignments,
            ExpressionEvaluator evaluator,
            int columnCount
    ) {
        this.child = Objects.requireNonNull(child, "child");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        if (columnCount < 1) {
            throw new IllegalArgumentException("columnCount must be >= 1");
        }
        this.columnCount = columnCount;
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
                Object[] updated = new Object[columnCount];
                Object[] current = tuple.values();
                System.arraycopy(current, 0, updated, 0, Math.min(current.length, columnCount));
                for (ResolvedAssignment assignment : assignments) {
                    updated[assignment.columnId() - 1] = evaluator.evaluate(assignment.value(), tuple);
                }
                tableStore.update(database, table, tuple.rowId(), updated);
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
