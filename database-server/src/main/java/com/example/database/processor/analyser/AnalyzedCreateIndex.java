package com.example.database.processor.analyser;

import java.util.List;
import java.util.Objects;

/**
 * Semantically valid CREATE INDEX with column ids resolved from the target table.
 */
public final class AnalyzedCreateIndex implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final String index;
    private final List<Integer> columnIds;

    public AnalyzedCreateIndex(String database, String table, String index, List<Integer> columnIds) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.index = Objects.requireNonNull(index, "index");
        this.columnIds = List.copyOf(Objects.requireNonNull(columnIds, "columnIds"));
        if (columnIds.isEmpty()) {
            throw new IllegalArgumentException("columnIds must not be empty");
        }
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public String index() {
        return index;
    }

    public List<Integer> columnIds() {
        return columnIds;
    }

    @Override
    public String toString() {
        return "AnalyzedCreateIndex{database=" + database + ", table=" + table
                + ", index=" + index + ", columnIds=" + columnIds + "}";
    }
}
