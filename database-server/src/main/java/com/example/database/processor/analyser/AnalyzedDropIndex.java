package com.example.database.processor.analyser;

import java.util.Objects;

/**
 * Semantically valid DROP INDEX after resolving which table owns the index name.
 */
public final class AnalyzedDropIndex implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final String index;

    public AnalyzedDropIndex(String database, String table, String index) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.index = Objects.requireNonNull(index, "index");
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

    @Override
    public String toString() {
        return "AnalyzedDropIndex{database=" + database + ", table=" + table + ", index=" + index + "}";
    }
}
