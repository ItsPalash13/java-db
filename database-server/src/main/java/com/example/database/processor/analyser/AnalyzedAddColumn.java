package com.example.database.processor.analyser;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.Objects;

/**
 * Semantically valid ALTER TABLE ADD COLUMN. Column id is assigned at execute time.
 */
public final class AnalyzedAddColumn implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final ColumnMetadata column;

    public AnalyzedAddColumn(String database, String table, ColumnMetadata column) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.column = Objects.requireNonNull(column, "column");
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public ColumnMetadata column() {
        return column;
    }

    @Override
    public String toString() {
        return "AnalyzedAddColumn{database=" + database + ", table=" + table + ", column=" + column + "}";
    }
}
