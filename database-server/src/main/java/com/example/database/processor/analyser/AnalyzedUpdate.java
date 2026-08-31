package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * UPDATE after SET columns and WHERE are bound. Indexes/columns travel with the
 * analyzed form so the planner stays catalog-free.
 */
public final class AnalyzedUpdate implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final List<ResolvedAssignment> assignments;
    private final Expression where;
    private final List<ColumnMetadata> columns;
    private final List<IndexMetadata> indexes;

    public AnalyzedUpdate(
            String database,
            String table,
            List<ResolvedAssignment> assignments,
            Expression where,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        this.where = where;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("assignments must not be empty");
        }
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

    public Optional<Expression> where() {
        return Optional.ofNullable(where);
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    public List<IndexMetadata> indexes() {
        return indexes;
    }

    @Override
    public String toString() {
        return "AnalyzedUpdate{database=" + database + ", table=" + table
                + ", assignments=" + assignments + ", where=" + where + "}";
    }
}
