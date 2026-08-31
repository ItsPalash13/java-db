package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DELETE after the table and optional WHERE are bound. Indexes/columns are for
 * access-path choice in the planner, not for CatalogManager.
 */
public final class AnalyzedDelete implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final Expression where;
    private final List<ColumnMetadata> columns;
    private final List<IndexMetadata> indexes;

    public AnalyzedDelete(
            String database,
            String table,
            Expression where,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
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
        return "AnalyzedDelete{database=" + database + ", table=" + table + ", where=" + where + "}";
    }
}
