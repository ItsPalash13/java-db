package com.example.database.processor.analyser;

import java.util.List;
import java.util.Objects;

/**
 * INSERT after arity and type checks. {@code values} is one slot per table column
 * in catalog order (omitted nullable columns already filled with null).
 */
public final class AnalyzedInsert implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final List<ResolvedInsertValue> values;

    public AnalyzedInsert(String database, String table, List<ResolvedInsertValue> values) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
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
        return "AnalyzedInsert{database=" + database + ", table=" + table + ", values=" + values + "}";
    }
}
