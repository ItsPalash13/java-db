package com.example.database.storage.catalog;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Logical table schema. {@link #tableId()} is empty until {@code CatalogManager} assigns it.
 */
public final class TableMetadata {

    private final OptionalInt tableId;
    private final String name;
    private final List<ColumnMetadata> columns;

    /** Unassigned table definition. */
    public static TableMetadata define(String name, List<ColumnMetadata> columns) {
        return new TableMetadata(OptionalInt.empty(), name, columns);
    }

    public TableMetadata(int tableId, String name, List<ColumnMetadata> columns) {
        this(OptionalInt.of(tableId), name, columns);
    }

    private TableMetadata(OptionalInt tableId, String name, List<ColumnMetadata> columns) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        this.name = Objects.requireNonNull(name, "name");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (tableId.isPresent() && tableId.getAsInt() < 1) {
            throw new IllegalArgumentException("tableId must be >= 1");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
    }

    public OptionalInt tableId() {
        return tableId;
    }

    public String name() {
        return name;
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }

    TableMetadata withAssignedIds(int tableId, List<ColumnMetadata> columns) {
        return new TableMetadata(tableId, name, columns);
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
                && name.equals(that.name)
                && columns.equals(that.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableId, name, columns);
    }

    @Override
    public String toString() {
        return "TableMetadata{tableId=" + tableId + ", name=" + name + ", columns=" + columns + "}";
    }
}
