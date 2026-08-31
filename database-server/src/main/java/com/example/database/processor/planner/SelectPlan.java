package com.example.database.processor.planner;

import com.example.database.processor.analyser.ResolvedProjection;
import com.example.database.processor.parser.ast.Expression;

import java.util.List;
import java.util.Objects;

/**
 * SELECT ready for a row executor. Access path is a label; DeferredRowExecutor ignores it.
 */
public final class SelectPlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final List<ResolvedProjection> projections;
    private final Expression where;
    private final AccessPath accessPath;

    public SelectPlan(
            String database,
            String table,
            List<ResolvedProjection> projections,
            Expression where,
            AccessPath accessPath
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        this.where = where;
        this.accessPath = Objects.requireNonNull(accessPath, "accessPath");
        if (projections.isEmpty()) {
            throw new IllegalArgumentException("projections must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.SELECT;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public List<ResolvedProjection> projections() {
        return projections;
    }

    public Expression where() {
        return where;
    }

    public AccessPath accessPath() {
        return accessPath;
    }

    @Override
    public String toString() {
        return "SelectPlan{database=" + database + ", table=" + table
                + ", projections=" + projections + ", accessPath=" + accessPath + "}";
    }
}
