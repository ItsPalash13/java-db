package com.example.database.processor.planner;

import java.util.Objects;

/**
 * DROP INDEX after the analyser resolved which table owns the index name.
 */
public final class DropIndexPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final String index;

    public DropIndexPlan(String database, String table, String index) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public QueryType queryType() {
        return QueryType.DROP_INDEX;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public String index() {
        return index;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DropIndexPlan that)) {
            return false;
        }
        return database.equals(that.database)
                && table.equals(that.table)
                && index.equals(that.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table, index);
    }

    @Override
    public String toString() {
        return "DropIndexPlan{database=" + database + ", table=" + table + ", index=" + index + "}";
    }
}
