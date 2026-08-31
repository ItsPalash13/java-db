package com.example.database.processor.planner;

import com.example.database.processor.parser.ast.Expression;

import java.util.Objects;

/**
 * DELETE with optional WHERE. Access path describes how to find matching rows.
 */
public final class DeletePlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final Expression where;
    private final AccessPath accessPath;

    public DeletePlan(String database, String table, Expression where, AccessPath accessPath) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
        this.accessPath = Objects.requireNonNull(accessPath, "accessPath");
    }

    @Override
    public QueryType queryType() {
        return QueryType.DELETE;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public Expression where() {
        return where;
    }

    public AccessPath accessPath() {
        return accessPath;
    }

    @Override
    public String toString() {
        return "DeletePlan{database=" + database + ", table=" + table + ", accessPath=" + accessPath + "}";
    }
}
