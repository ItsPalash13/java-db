package com.example.database.processor.executor.engine.volcano;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.analyser.ResolvedProjection;
import com.example.database.processor.executor.ExecutionException;
import com.example.database.processor.executor.QueryExecutor;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.executor.engine.volcano.operator.DeleteOperator;
import com.example.database.processor.executor.engine.volcano.operator.Filter;
import com.example.database.processor.executor.engine.volcano.operator.InsertOperator;
import com.example.database.processor.executor.engine.volcano.operator.Project;
import com.example.database.processor.executor.engine.volcano.operator.SeqScan;
import com.example.database.processor.executor.engine.volcano.operator.UpdateOperator;
import com.example.database.processor.executor.engine.volcano.operator.VolcanoOperator;
import com.example.database.processor.planner.DeletePlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.processor.planner.InsertPlan;
import com.example.database.processor.planner.SelectPlan;
import com.example.database.processor.planner.UpdatePlan;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.lock.LockException;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pull-iterator DML/DQL executor. Compiles declarative plans into Volcano operators
 * over {@link TableStore}. Takes table IS/IX and row S/X locks at execute time.
 */
public final class VolcanoExecutor implements QueryExecutor {

    private final TableStore tableStore;
    private final LockManager lockManager;
    private final TransactionManager transactionManager;

    public VolcanoExecutor(TableStore tableStore, LockManager lockManager, TransactionManager transactionManager) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof SelectPlan select) {
            return runLocked(() -> executeSelect(select));
        }
        if (plan instanceof InsertPlan insert) {
            return runLocked(() -> executeInsert(insert));
        }
        if (plan instanceof UpdatePlan update) {
            return runLocked(() -> executeUpdate(update));
        }
        if (plan instanceof DeletePlan delete) {
            return runLocked(() -> executeDelete(delete));
        }
        throw new ExecutionException("VolcanoExecutor cannot execute " + plan.queryType());
    }

    private QueryResult executeSelect(SelectPlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IS);
        try {
            ExpressionEvaluator evaluator = evaluator(plan.columns());
            VolcanoOperator root = new SeqScan(tableStore, lockManager, plan.database(), plan.table());
            if (plan.where() != null) {
                root = new Filter(root, plan.where(), evaluator);
            }
            root = new Project(root, plan.projections());
            List<List<Object>> rows = drain(root);
            return QueryResult.resultSet(toWireColumns(plan.projections()), rows);
        } finally {
            releaseStatementLocks();
        }
    }

    private QueryResult executeInsert(InsertPlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IX);
        try {
            VolcanoOperator root = new InsertOperator(
                    tableStore,
                    lockManager,
                    plan.database(),
                    plan.table(),
                    plan.values()
            );
            drain(root);
            return QueryResult.ok();
        } finally {
            releaseStatementLocks();
        }
    }

    private QueryResult executeUpdate(UpdatePlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IX);
        try {
            ExpressionEvaluator evaluator = evaluator(plan.columns());
            VolcanoOperator scan = new SeqScan(tableStore, plan.database(), plan.table());
            if (plan.where() != null) {
                scan = new Filter(scan, plan.where(), evaluator);
            }
            VolcanoOperator root = new UpdateOperator(
                    scan,
                    tableStore,
                    lockManager,
                    plan.database(),
                    plan.table(),
                    plan.assignments(),
                    evaluator,
                    plan.columns().size()
            );
            drain(root);
            return QueryResult.ok();
        } finally {
            releaseStatementLocks();
        }
    }

    private QueryResult executeDelete(DeletePlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IX);
        try {
            ExpressionEvaluator evaluator = evaluator(plan.columns());
            VolcanoOperator scan = new SeqScan(tableStore, plan.database(), plan.table());
            if (plan.where() != null) {
                scan = new Filter(scan, plan.where(), evaluator);
            }
            VolcanoOperator root = new DeleteOperator(
                    scan,
                    tableStore,
                    lockManager,
                    plan.database(),
                    plan.table()
            );
            drain(root);
            return QueryResult.ok();
        } finally {
            releaseStatementLocks();
        }
    }

    /**
     * Implicit statements wrap runInTransaction; explicit BEGIN sessions reuse the open txn id
     * and keep table/row locks until COMMIT/ROLLBACK.
     */
    private <T> T runLocked(Supplier<T> action) {
        if (transactionManager.inExplicitTransaction()) {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return action.get();
            } catch (LockException e) {
                lockManager.unlockAllForOwner();
                throw e;
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return action.get();
            } catch (LockException e) {
                lockManager.unlockAllForOwner();
                throw e;
            } finally {
                lockManager.clearOwnerBinding();
            }
        });
    }

    /** Explicit txn holds scoped locks for the whole session; implicit releases per statement. */
    private void releaseStatementLocks() {
        if (!transactionManager.inExplicitTransaction()) {
            lockManager.unlockAllForOwner();
        }
    }

    private static List<List<Object>> drain(VolcanoOperator root) {
        root.open();
        try {
            List<List<Object>> rows = new ArrayList<>();
            Tuple tuple;
            while ((tuple = root.next()) != null) {
                rows.add(Arrays.asList(tuple.values()));
            }
            return rows;
        } finally {
            root.close();
        }
    }

    private static ExpressionEvaluator evaluator(List<ColumnMetadata> columns) {
        Map<String, Integer> byName = new HashMap<>();
        for (ColumnMetadata column : columns) {
            byName.put(column.name(), column.columnId().orElseThrow());
        }
        return new ExpressionEvaluator(byName);
    }

    private static List<WireMessage.ResultSet.Column> toWireColumns(List<ResolvedProjection> projections) {
        List<WireMessage.ResultSet.Column> columns = new ArrayList<>(projections.size());
        for (int i = 0; i < projections.size(); i++) {
            ResolvedProjection projection = projections.get(i);
            String name = projection.name().orElse("col" + (i + 1));
            columns.add(new WireMessage.ResultSet.Column(name, projection.type().name()));
        }
        return columns;
    }
}
