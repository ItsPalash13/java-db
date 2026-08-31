package com.example.database.processor.planner;

import com.example.database.processor.analyser.ResolvedInsertValue;

import java.util.List;
import java.util.Objects;

/**
 * INSERT of already-typed values in catalog column order. Always a heap insert;
 * no access path.
 */
public final class InsertPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final List<ResolvedInsertValue> values;

    public InsertPlan(String database, String table, List<ResolvedInsertValue> values) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.INSERT;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public List<ResolvedInsertValue> values() {
        return values;
    }

    @Override
    public String toString() {
        return "InsertPlan{database=" + database + ", table=" + table + ", values=" + values + "}";
    }
}
