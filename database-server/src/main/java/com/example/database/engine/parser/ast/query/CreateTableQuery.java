package com.example.database.engine.parser.ast.query;

import com.example.database.engine.parser.ast.Query;

import java.util.List;
import java.util.Objects;

/**
 * CREATE TABLE name (columns).
 */
public final class CreateTableQuery implements Query {

    private final String table;
    private final List<String> columns;

    public CreateTableQuery(String table, List<String> columns) {
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    }

    public String table() {
        return table;
    }

    public List<String> columns() {
        return columns;
    }

    @Override
    public String toString() {
        return "CreateTableQuery{table=" + table + ", columns=" + columns + "}";
    }
}
