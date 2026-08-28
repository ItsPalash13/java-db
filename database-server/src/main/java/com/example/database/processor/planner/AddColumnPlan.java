package com.example.database.processor.planner;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.Objects;

/**
 * ALTER TABLE ADD COLUMN ready for command execution. Column id stays empty until
 * {@code CatalogManager.addColumn} assigns it.
 */
public final class AddColumnPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final ColumnMetadata column;

    public AddColumnPlan(String database, String table, ColumnMetadata column) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.column = Objects.requireNonNull(column, "column");
    }

    @Override
    public QueryType queryType() {
        return QueryType.ADD_COLUMN;
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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AddColumnPlan that)) {
            return false;
        }
        return database.equals(that.database)
                && table.equals(that.table)
                && column.equals(that.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table, column);
    }

    @Override
    public String toString() {
        return "AddColumnPlan{database=" + database + ", table=" + table + ", column=" + column + "}";
    }
}
