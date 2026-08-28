package com.example.database.storage.catalog;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Logical table schema. {@link #tableId()} is empty until {@code CatalogManager} assigns it.
 * {@link #database()} is the owning database folder name.
 * Index definitions live here until {@link com.example.database.storage.index.IndexStore} exists.
 */
public final class TableMetadata {

    private final OptionalInt tableId;
    private final String database;
    private final String name;
    private final List<ColumnMetadata> columns;
    private final List<IndexMetadata> indexes;

    /** Unassigned table definition with no indexes. */
    public static TableMetadata define(String database, String name, List<ColumnMetadata> columns) {
        return new TableMetadata(OptionalInt.empty(), database, name, columns, List.of());
    }

    public TableMetadata(int tableId, String database, String name, List<ColumnMetadata> columns) {
        this(tableId, database, name, columns, List.of());
    }

    public TableMetadata(
            int tableId,
            String database,
            String name,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this(OptionalInt.of(tableId), database, name, columns, indexes);
    }

    private TableMetadata(
            OptionalInt tableId,
            String database,
            String name,
            List<ColumnMetadata> columns,
            List<IndexMetadata> indexes
    ) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        this.database = Objects.requireNonNull(database, "database");
        this.name = Objects.requireNonNull(name, "name");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
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

    TableMetadata withAssignedIds(int tableId, List<ColumnMetadata> columns) {
        return new TableMetadata(tableId, database, name, columns, indexes);
    }

    TableMetadata withColumns(List<ColumnMetadata> columns) {
        if (tableId.isEmpty()) {
            throw new IllegalStateException("tableId must be assigned before withColumns");
        }
        return new TableMetadata(tableId.getAsInt(), database, name, columns, indexes);
    }

    TableMetadata withIndexes(List<IndexMetadata> indexes) {
        if (tableId.isEmpty()) {
            throw new IllegalStateException("tableId must be assigned before withIndexes");
        }
        return new TableMetadata(tableId.getAsInt(), database, name, columns, indexes);
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
                && indexes.equals(that.indexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableId, database, name, columns, indexes);
    }

    @Override
    public String toString() {
        return "TableMetadata{tableId=" + tableId
                + ", database=" + database
                + ", name=" + name
                + ", columns=" + columns
                + ", indexes=" + indexes
                + "}";
    }
}
