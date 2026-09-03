package com.example.database.processor.planner;

import com.example.database.storage.catalog.ColumnMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CREATE TABLE ready for command execution. Column ids stay empty —
 * {@code CatalogManager.createTable} assigns them, not the planner.
 * {@link #primaryKeyColumn()} carries the PK column name when declared.
 */
public final class CreateTablePlan implements ExecutionPlan {

    private final String database;
    private final String table;
    private final List<ColumnMetadata> columns;
    private final Optional<String> primaryKeyColumn;

    public CreateTablePlan(String database, String table, List<ColumnMetadata> columns) {
        this(database, table, columns, Optional.empty());
    }

    public CreateTablePlan(String database, String table, List<ColumnMetadata> columns,
                           Optional<String> primaryKeyColumn) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.primaryKeyColumn = Objects.requireNonNull(primaryKeyColumn, "primaryKeyColumn");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
    }

    @Override
    public QueryType queryType() {
        return QueryType.CREATE_TABLE;
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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreateTablePlan that)) {
            return false;
        }
        return database.equals(that.database)
                && table.equals(that.table)
                && columns.equals(that.columns)
                && primaryKeyColumn.equals(that.primaryKeyColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table, columns, primaryKeyColumn);
    }

    @Override
    public String toString() {
        return "CreateTablePlan{database=" + database + ", table=" + table
                + ", columns=" + columns + ", primaryKeyColumn=" + primaryKeyColumn + "}";
    }
}
