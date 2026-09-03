package com.example.database.processor.planner;

import java.util.List;
import java.util.Objects;

/**
 * CREATE INDEX ready for command execution. Column ids are already resolved.
 */
public final class CreateIndexPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final String index;
    private final List<Integer> columnIds;
    private final boolean unique;

    public CreateIndexPlan(String database, String table, String index, List<Integer> columnIds, boolean unique) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.index = Objects.requireNonNull(index, "index");
        this.columnIds = List.copyOf(Objects.requireNonNull(columnIds, "columnIds"));
        this.unique = unique;
        if (columnIds.isEmpty()) {
            throw new IllegalArgumentException("columnIds must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.CREATE_INDEX;
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

    public List<Integer> columnIds() {
        return columnIds;
    }

    public boolean unique() {
        return unique;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreateIndexPlan that)) {
            return false;
        }
        return database.equals(that.database)
                && table.equals(that.table)
                && index.equals(that.index)
                && columnIds.equals(that.columnIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table, index, columnIds);
    }

    @Override
    public String toString() {
        return "CreateIndexPlan{database=" + database + ", table=" + table
                + ", index=" + index + ", columnIds=" + columnIds + "}";
    }
}
