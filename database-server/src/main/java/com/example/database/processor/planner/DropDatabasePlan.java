package com.example.database.processor.planner;

import java.util.Objects;

/**
 * DROP DATABASE ready for command execution. Directory must be empty.
 */
public final class DropDatabasePlan implements ExecutionPlan {

    private final String database;

    public DropDatabasePlan(String database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public QueryType queryType() {
        return QueryType.DROP_DATABASE;
    }

    public String database() {
        return database;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DropDatabasePlan that)) {
            return false;
        }
        return database.equals(that.database);
    }

    @Override
    public int hashCode() {
        return database.hashCode();
    }

    @Override
    public String toString() {
        return "DropDatabasePlan{database=" + database + "}";
    }
}
