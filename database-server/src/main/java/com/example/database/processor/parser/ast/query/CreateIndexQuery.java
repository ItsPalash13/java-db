package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;

/**
 * CREATE [UNIQUE] INDEX name ON database.table (columns).
 */
public final class CreateIndexQuery implements Query {

    private final String index;
    private final QualifiedTable table;
    private final List<String> columns;
    private final boolean unique;

    public CreateIndexQuery(String index, QualifiedTable table, List<String> columns) {
        this(index, table, columns, false);
    }

    public CreateIndexQuery(String index, QualifiedTable table, List<String> columns, boolean unique) {
        this.index = Objects.requireNonNull(index, "index");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.unique = unique;
    }

    public String index() {
        return index;
    }

    public QualifiedTable table() {
        return table;
    }

    public List<String> columns() {
        return columns;
    }

    public boolean unique() {
        return unique;
    }

    @Override
    public String toString() {
        return "CreateIndexQuery{index=" + index + ", table=" + table + ", columns=" + columns
                + ", unique=" + unique + "}";
    }
}
