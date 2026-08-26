package com.example.database.processor.planner;

import java.util.Objects;

/**
 * DROP TABLE ready for command execution. Removes the table catalog file and empty folder.
 */
public final class DropTablePlan implements ExecutionPlan {

    private final String database;
    private final String table;

    public DropTablePlan(String database, String table) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public QueryType queryType() {
        return QueryType.DROP_TABLE;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DropTablePlan that)) {
            return false;
        }
        return database.equals(that.database) && table.equals(that.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table);
    }

    @Override
    public String toString() {
        return "DropTablePlan{database=" + database + ", table=" + table + "}";
    }
}
