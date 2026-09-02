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
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.lock.LockException;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.transaction.IsolationLevel;
import com.example.database.storage.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pull-iterator DML/DQL executor. READ COMMITTED: S/IS released at statement end;
 * row/table X/IX held until COMMIT/ABORT (Strict 2PL on writes).
 */
public final class VolcanoExecutor implements QueryExecutor {

    private final TableStore tableStore;
    private final LockManager lockManager;
    private final TransactionManager transactionManager;
    private final CatalogManager catalogManager;

    public VolcanoExecutor(
            TableStore tableStore,
            LockManager lockManager,
            TransactionManager transactionManager,
            CatalogManager catalogManager
    ) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
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
            releaseStatementSharedLocks();
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
            releaseStatementSharedLocks();
        }
    }

    private QueryResult executeUpdate(UpdatePlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IX);
        try {
            ExpressionEvaluator evaluator = evaluator(plan.columns());
            VolcanoOperator scan = WriteScanPrefilter.scan(
                    tableStore,
                    plan.database(),
                    plan.table(),
                    plan.where(),
                    plan.columns(),
                    evaluator
            );
            VolcanoOperator root = new UpdateOperator(
                    scan,
                    tableStore,
                    lockManager,
                    plan.database(),
                    plan.table(),
                    plan.assignments(),
                    plan.where(),
                    evaluator,
                    plan.columns().size()
            );
            drain(root);
            return QueryResult.ok();
        } finally {
            releaseStatementSharedLocks();
        }
    }

    private QueryResult executeDelete(DeletePlan plan) {
        lockManager.lockTable(plan.database(), plan.table(), LockMode.IX);
        try {
            ExpressionEvaluator evaluator = evaluator(plan.columns());
            VolcanoOperator scan = WriteScanPrefilter.scan(
                    tableStore,
                    plan.database(),
                    plan.table(),
                    plan.where(),
                    plan.columns(),
                    evaluator
            );
            VolcanoOperator root = new DeleteOperator(
                    scan,
                    tableStore,
                    lockManager,
                    plan.database(),
                    plan.table(),
                    plan.where(),
                    evaluator
            );
            drain(root);
            return QueryResult.ok();
        } finally {
            releaseStatementSharedLocks();
        }
    }

    private <T> T runLocked(Supplier<T> action) {
        if (transactionManager.inExplicitTransaction()) {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return action.get();
            } catch (LockException e) {
                // Undo + lock release must happen before ERROR returns to the client.
                transactionManager.rollbackExplicit(lockManager, catalogManager, tableStore);
                throw e;
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(lockManager, tableStore, action);
    }

    /** READ COMMITTED releases S/IS at statement end; X/IX stay until COMMIT/ABORT. */
    private void releaseStatementSharedLocks() {
        if (transactionManager.isolationLevel() == IsolationLevel.READ_COMMITTED) {
            lockManager.unlockSharedForOwner();
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
