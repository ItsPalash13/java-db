package com.example.database.processor.analyser;

import java.util.Objects;

/**
 * Semantically valid ALTER TABLE DROP COLUMN. Catalog removes the column by name at execute time.
 */
public final class AnalyzedDropColumn implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final String column;

    public AnalyzedDropColumn(String database, String table, String column) {
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

    public String column() {
        return column;
    }

    @Override
    public String toString() {
        return "AnalyzedDropColumn{database=" + database + ", table=" + table + ", column=" + column + "}";
    }
}
