package com.example.database.storage.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Logical table schema. {@link #tableId()} is empty until {@code CatalogManager} assigns it.
 * {@link #database()} is the owning database folder name.
 * {@link #primaryKeyColumn()} names the column declared {@code PRIMARY KEY} (unique + NOT NULL).
 * Index definitions live here until {@link com.example.database.storage.index.IndexStore} exists.
 */
public final class TableMetadata {

    private final OptionalInt tableId;
    private final String database;
    private final String name;
    private final List<ColumnMetadata> columns;
    private final List<IndexMetadata> indexes;
    /** Column name declared PRIMARY KEY, or empty if none. */
    private final Optional<String> primaryKeyColumn;

    /** Unassigned table definition with no indexes. */
    public static TableMetadata define(String database, String name, List<ColumnMetadata> columns) {
        return new TableMetadata(OptionalInt.empty(), database, name, columns, List.of(), Optional.empty());
    }

    /** Unassigned table definition with a primary key column. */
    public static TableMetadata define(String database, String name, List<ColumnMetadata> columns,
                                       Optional<String> primaryKeyColumn) {
        return new TableMetadata(OptionalInt.empty(), database, name, columns, List.of(), primaryKeyColumn);
    }

    public TableMetadata(int tableId, String database, String name, List<ColumnMetadata> columns) {
        this(tableId, database, name, columns, List.of(), Optional.empty());
    }

    public TableMetadata(
            int tableId,
            String database,
            String name,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this(OptionalInt.of(tableId), database, name, columns, indexes, Optional.empty());
    }

    public TableMetadata(
            int tableId,
            String database,
            String name,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes,
            Optional<String> primaryKeyColumn
    ) {
        this(OptionalInt.of(tableId), database, name, columns, indexes, primaryKeyColumn);
    }

    private TableMetadata(
            OptionalInt tableId,
            String database,
            String name,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes,
            Optional<String> primaryKeyColumn
    ) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        this.database = Objects.requireNonNull(database, "database");
        this.name = Objects.requireNonNull(name, "name");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
        this.primaryKeyColumn = Objects.requireNonNull(primaryKeyColumn, "primaryKeyColumn");
        if (tableId.isPresent() && tableId.getAsInt() < 1) {
            throw new IllegalArgumentException("tableId must be >= 1");
        }
        if (database.isBlank()) {
            throw new IllegalArgumentException("database name must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
    }

    public OptionalInt tableId() {
        return tableId;
    }

    public String database() {
        return database;
    }

    public String name() {
        return name;
    }

    /** Error and log form: {@code shop.users}. */
    public String qualifiedName() {
        return database + "." + name;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    public List<IndexMetadata> indexes() {
        return indexes;
    }

    /** Column name declared PRIMARY KEY, or empty if the table has no PK. */
    public Optional<String> primaryKeyColumn() {
        return primaryKeyColumn;
    }

    TableMetadata withAssignedIds(int tableId, List<ColumnMetadata> columns) {
        return new TableMetadata(tableId, database, name, columns, indexes, primaryKeyColumn);
    }

    TableMetadata withColumns(List<ColumnMetadata> columns) {
        if (tableId.isEmpty()) {
            throw new IllegalStateException("tableId must be assigned before withColumns");
        }
        return new TableMetadata(tableId.getAsInt(), database, name, columns, indexes, primaryKeyColumn);
    }

    TableMetadata withIndexes(List<IndexMetadata> indexes) {
        if (tableId.isEmpty()) {
            throw new IllegalStateException("tableId must be assigned before withIndexes");
        }
        return new TableMetadata(tableId.getAsInt(), database, name, columns, indexes, primaryKeyColumn);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TableMetadata that)) {
            return false;
        }
        return tableId.equals(that.tableId)
                && database.equals(that.database)
                && name.equals(that.name)
                && columns.equals(that.columns)
                && indexes.equals(that.indexes)
                && primaryKeyColumn.equals(that.primaryKeyColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableId, database, name, columns, indexes, primaryKeyColumn);
    }

    @Override
    public String toString() {
        return "TableMetadata{tableId=" + tableId
                + ", database=" + database
                + ", name=" + name
                + ", columns=" + columns
                + ", indexes=" + indexes
                + ", primaryKeyColumn=" + primaryKeyColumn
                + "}";
    }
}

