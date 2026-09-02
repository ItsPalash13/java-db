package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.ExpressionEvaluator;
import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.processor.parser.ast.Expression;
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
    private final Expression where;
    private final ExpressionEvaluator evaluator;

    public DeleteOperator(
            VolcanoOperator child,
            TableStore tableStore,
            LockManager lockManager,
            String database,
            String table,
            Expression where,
            ExpressionEvaluator evaluator
    ) {
        this.child = Objects.requireNonNull(child, "child");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        while (true) {
            Tuple snapshotRow = child.next();
            if (snapshotRow == null) {
                return null;
            }
            long rowId = snapshotRow.rowId();
            boolean heldBefore = lockManager.holdsRowExclusive(database, table, rowId);
            if (!heldBefore) {
                lockManager.lockRow(database, table, rowId, LockMode.X);
            }
            Tuple current = tableStore.findByRowId(database, table, rowId).orElse(null);
            if (current == null || (where != null && !evaluator.matches(where, current))) {
                if (!heldBefore) {
                    lockManager.unlockRow(database, table, rowId, LockMode.X);
                }
                continue;
            }
            tableStore.delete(database, table, rowId);
        }
    }

    @Override
    public void close() {
        child.close();
    }
}
