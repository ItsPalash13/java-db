package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SELECT after name resolution. {@code indexes} and {@code columns} are copied from
 * the catalog so the planner can pick table vs index scan without reading CatalogManager.
 */
public final class AnalyzedSelect implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final List<ResolvedProjection> projections;
    private final Expression where;
    private final List<ColumnMetadata> columns;
    private final List<IndexMetadata> indexes;

    public AnalyzedSelect(
            String database,
            String table,
            List<ResolvedProjection> projections,
            Expression where,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        this.where = where;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
        if (projections.isEmpty()) {
            throw new IllegalArgumentException("projections must not be empty");
        }
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
        return "AnalyzedSelect{database=" + database + ", table=" + table
                + ", projections=" + projections + ", where=" + where + "}";
    }
}
