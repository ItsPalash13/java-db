package com.example.database.processor.analyser;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Semantically valid CREATE TABLE ready for planning. Column ids are not assigned yet.
 * {@link #primaryKeyColumn()} carries the PK column name when declared.
 */
public final class AnalyzedCreateTable implements AnalyzedQuery {

    private final String database;
    private final String table;
    private final List<ColumnMetadata> columns;
    private final Optional<String> primaryKeyColumn;

    public AnalyzedCreateTable(String database, String table, List<ColumnMetadata> columns) {
        this(database, table, columns, Optional.empty());
    }

    public AnalyzedCreateTable(String database, String table, List<ColumnMetadata> columns,
                               Optional<String> primaryKeyColumn) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.primaryKeyColumn = Objects.requireNonNull(primaryKeyColumn, "primaryKeyColumn");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    /** Column name declared PRIMARY KEY, or empty. */
    public Optional<String> primaryKeyColumn() {
        return primaryKeyColumn;
    }

    @Override
    public String toString() {
        return "AnalyzedCreateTable{database=" + database + ", table=" + table
                + ", columns=" + columns + ", primaryKeyColumn=" + primaryKeyColumn + "}";
    }
}
