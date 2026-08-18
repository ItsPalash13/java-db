package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;

/**
 * CREATE INDEX name ON table (columns).
 */
public final class CreateIndexQuery implements Query {

    private final String index;
    private final String table;
    private final List<String> columns;

    public CreateIndexQuery(String index, String table, List<String> columns) {
        this.index = Objects.requireNonNull(index, "index");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    }

    public String index() {
        return index;
    }

    public String table() {
        return table;
    }

    public List<String> columns() {
        return columns;
    }

    @Override
    public String toString() {
        return "CreateIndexQuery{index=" + index + ", table=" + table + ", columns=" + columns + "}";
    }
}
