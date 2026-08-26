package com.example.database.processor.analyser;

import java.util.Objects;

/**
 * Semantically valid DROP TABLE. Executor removes the per-table catalog file and empty folder.
 */
public final class AnalyzedDropTable implements AnalyzedQuery {

    private final String database;
    private final String table;

    public AnalyzedDropTable(String database, String table) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    @Override
    public String toString() {
        return "AnalyzedDropTable{database=" + database + ", table=" + table + "}";
    }
}
