package com.example.database.processor.planner;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;

/**
 * CREATE TABLE ready for command execution. Column ids stay empty —
 * {@code CatalogManager.createTable} assigns them, not the planner.
 */
public final class CreateTablePlan implements ExecutionPlan {

    private final String table;
    private final List<ColumnMetadata> columns;

    public CreateTablePlan(String table, List<ColumnMetadata> columns) {
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.CREATE_TABLE;
    }

    public String table() {
        return table;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreateTablePlan that)) {
            return false;
        }
        return table.equals(that.table) && columns.equals(that.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, columns);
    }

    @Override
    public String toString() {
        return "CreateTablePlan{table=" + table + ", columns=" + columns + "}";
    }
}
