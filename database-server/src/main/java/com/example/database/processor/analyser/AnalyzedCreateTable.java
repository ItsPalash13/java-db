package com.example.database.processor.analyser;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Semantically valid CREATE TABLE ready for planning. Column ids are not assigned yet.
 */
public final class AnalyzedCreateTable implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final List<ColumnMetadata> columns;

    public AnalyzedCreateTable(String database, String table, List<ColumnMetadata> columns) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    @Override
    public String toString() {
        return "AnalyzedCreateTable{database=" + database + ", table=" + table + ", columns=" + columns + "}";
    }
}
