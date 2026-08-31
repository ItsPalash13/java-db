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
import com.example.database.storage.table.TableStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pull-iterator DML/DQL executor. Compiles declarative plans into Volcano operators
 * over {@link TableStore}. INDEX_SCAN is treated as SeqScan until IndexStore exists.
 */
public final class VolcanoExecutor implements QueryExecutor {

    private final TableStore tableStore;

    public VolcanoExecutor(TableStore tableStore) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof SelectPlan select) {
            return executeSelect(select);
        }
        if (plan instanceof InsertPlan insert) {
            return executeInsert(insert);
        }
        if (plan instanceof UpdatePlan update) {
            return executeUpdate(update);
        }
        if (plan instanceof DeletePlan delete) {
            return executeDelete(delete);
        }
        throw new ExecutionException("VolcanoExecutor cannot execute " + plan.queryType());
    }

    private QueryResult executeSelect(SelectPlan plan) {
        // INDEX_SCAN uses SeqScan until B+Tree; Filter still applies WHERE.
        ExpressionEvaluator evaluator = evaluator(plan.columns());
        VolcanoOperator root = new SeqScan(tableStore, plan.database(), plan.table());
        if (plan.where() != null) {
            root = new Filter(root, plan.where(), evaluator);
        }
        root = new Project(root, plan.projections());
        List<List<Object>> rows = drain(root);
        return QueryResult.resultSet(toWireColumns(plan.projections()), rows);
    }

    private QueryResult executeInsert(InsertPlan plan) {
        VolcanoOperator root = new InsertOperator(
                tableStore,
                plan.database(),
                plan.table(),
                plan.values()
        );
        drain(root);
        return QueryResult.ok();
    }

    private QueryResult executeUpdate(UpdatePlan plan) {
        ExpressionEvaluator evaluator = evaluator(plan.columns());
        VolcanoOperator scan = new SeqScan(tableStore, plan.database(), plan.table());
        if (plan.where() != null) {
            scan = new Filter(scan, plan.where(), evaluator);
        }
        VolcanoOperator root = new UpdateOperator(
                scan,
                tableStore,
                plan.database(),
                plan.table(),
                plan.assignments(),
                evaluator,
                plan.columns().size()
        );
        drain(root);
        return QueryResult.ok();
    }

    private QueryResult executeDelete(DeletePlan plan) {
        ExpressionEvaluator evaluator = evaluator(plan.columns());
        VolcanoOperator scan = new SeqScan(tableStore, plan.database(), plan.table());
        if (plan.where() != null) {
            scan = new Filter(scan, plan.where(), evaluator);
        }
        VolcanoOperator root = new DeleteOperator(scan, tableStore, plan.database(), plan.table());
        drain(root);
        return QueryResult.ok();
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
