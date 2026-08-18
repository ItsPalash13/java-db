package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

import java.util.Objects;

/**
 * DROP TABLE name.
 */
public final class DropTableQuery implements Query {

    private final String table;

    public DropTableQuery(String table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    public String table() {
        return table;
    }

    @Override
    public String toString() {
        return "DropTableQuery{table=" + table + "}";
    }
}
