package com.example.database.processor.planner;

import java.util.Objects;

/**
 * CREATE DATABASE ready for command execution. Directory name is the database name.
 */
public final class CreateDatabasePlan implements ExecutionPlan {

    private final String database;

    public CreateDatabasePlan(String database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public QueryType queryType() {
        return QueryType.CREATE_DATABASE;
    }

    public String database() {
        return database;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreateDatabasePlan that)) {
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
        return "CreateDatabasePlan{database=" + database + "}";
    }
}
