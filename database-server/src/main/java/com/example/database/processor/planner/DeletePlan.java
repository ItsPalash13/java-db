package com.example.database.processor.planner;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;

/**
 * DELETE with optional WHERE. Columns are for WHERE name resolution at execute.
 */
public final class DeletePlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final Expression where;
    private final List<ColumnMetadata> columns;
    private final AccessPath accessPath;

    public DeletePlan(
            String database,
            String table,
            Expression where,
            List<ColumnMetadata> columns,
            AccessPath accessPath
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
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

    public List<ColumnMetadata> columns() {
        return columns;
    }

    public AccessPath accessPath() {
        return accessPath;
    }

    @Override
    public String toString() {
        return "DeletePlan{database=" + database + ", table=" + table + ", accessPath=" + accessPath + "}";
    }
}
