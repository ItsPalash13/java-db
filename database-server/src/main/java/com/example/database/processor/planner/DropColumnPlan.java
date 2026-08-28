package com.example.database.processor.planner;

import java.util.Objects;

/**
 * ALTER TABLE DROP COLUMN ready for command execution.
 */
public final class DropColumnPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final String column;

    public DropColumnPlan(String database, String table, String column) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.column = Objects.requireNonNull(column, "column");
    }

    @Override
    public QueryType queryType() {
        return QueryType.DROP_COLUMN;
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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DropColumnPlan that)) {
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
        return "DropColumnPlan{database=" + database + ", table=" + table + ", column=" + column + "}";
    }
}
