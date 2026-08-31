package com.example.database.processor.planner;

import com.example.database.processor.analyser.ResolvedAssignment;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;

/**
 * UPDATE with resolved SET assignments. Columns travel with the plan so Volcano
 * can evaluate WHERE / SET expressions without CatalogManager.
 */
public final class UpdatePlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final List<ResolvedAssignment> assignments;
    private final Expression where;
    private final List<ColumnMetadata> columns;
    private final AccessPath accessPath;

    public UpdatePlan(
            String database,
            String table,
            List<ResolvedAssignment> assignments,
            Expression where,
            List<ColumnMetadata> columns,
            AccessPath accessPath
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        this.where = where;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.accessPath = Objects.requireNonNull(accessPath, "accessPath");
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("assignments must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.UPDATE;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public List<ResolvedAssignment> assignments() {
        return assignments;
    }

    public Expression where() {
        return where;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    public AccessPath accessPath() {
        return accessPath;
    }

    @Override
    public String toString() {
        return "UpdatePlan{database=" + database + ", table=" + table
                + ", assignments=" + assignments + ", accessPath=" + accessPath + "}";
    }
}
