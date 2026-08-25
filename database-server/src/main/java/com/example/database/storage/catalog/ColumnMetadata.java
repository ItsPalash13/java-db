package com.example.database.storage.catalog;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * One column in a table's schema. {@link #columnId()} is empty until {@code CatalogManager} assigns it.
 */
public final class ColumnMetadata {

    private final OptionalInt columnId;
    private final String name;
    private final ColumnType type;
    private final boolean nullable;

    /** Unassigned column definition; nullable defaults to {@code true}. */
    public static ColumnMetadata define(String name, ColumnType type) {
        return new ColumnMetadata(OptionalInt.empty(), name, type, true);
    }

    public static ColumnMetadata define(String name, ColumnType type, boolean nullable) {
        return new ColumnMetadata(OptionalInt.empty(), name, type, nullable);
    }

    public ColumnMetadata(int columnId, String name, ColumnType type, boolean nullable) {
        this(OptionalInt.of(columnId), name, type, nullable);
    }

    private ColumnMetadata(OptionalInt columnId, String name, ColumnType type, boolean nullable) {
        this.columnId = Objects.requireNonNull(columnId, "columnId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.nullable = nullable;
        if (columnId.isPresent() && columnId.getAsInt() < 1) {
            throw new IllegalArgumentException("columnId must be >= 1");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
    }

    public OptionalInt columnId() {
        return columnId;
    }

    public String name() {
        return name;
    }

    public ColumnType type() {
        return type;
    }

    public boolean nullable() {
        return nullable;
    }

    ColumnMetadata withId(int columnId) {
        return new ColumnMetadata(columnId, name, type, nullable);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnMetadata that)) {
            return false;
        }
        return columnId.equals(that.columnId)
                && name.equals(that.name)
                && type == that.type
                && nullable == that.nullable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnId, name, type, nullable);
    }

    @Override
    public String toString() {
        return "ColumnMetadata{columnId=" + columnId
                + ", name=" + name
                + ", type=" + type
                + ", nullable=" + nullable
                + "}";
    }
}
