package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;

/**
 * CREATE TABLE name (column type, ...).
 */
public final class CreateTableQuery implements Query {

    private final String table;
    private final List<ColumnDefinition> columns;

    public CreateTableQuery(String table, List<ColumnDefinition> columns) {
        this.table = Objects.requireNonNull(table, "table");
        // copyOf so callers cannot mutate the AST after parse/analyse.
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    }

    public String table() {
        return table;
    }

    public List<ColumnDefinition> columns() {
        return columns;
    }

    @Override
    public String toString() {
        return "CreateTableQuery{table=" + table + ", columns=" + columns + "}";
    }
}
