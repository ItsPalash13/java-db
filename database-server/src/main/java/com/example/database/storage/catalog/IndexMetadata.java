package com.example.database.storage.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Catalog-only index definition. {@link IndexStore} builds physical trees later from these ids.
 */
public final class IndexMetadata {

    private final String name;
    private final List<Integer> columnIds;
    private final boolean unique;

    /** Phase 1 indexes are non-unique definitions only. */
    public static IndexMetadata define(String name, List<Integer> columnIds) {
        return new IndexMetadata(name, columnIds, false);
    }

    public IndexMetadata(String name, List<Integer> columnIds, boolean unique) {
        this.name = Objects.requireNonNull(name, "name");
        this.columnIds = List.copyOf(Objects.requireNonNull(columnIds, "columnIds"));
        this.unique = unique;
        if (name.isBlank()) {
            throw new IllegalArgumentException("index name must not be blank");
        }
        if (columnIds.isEmpty()) {
            throw new IllegalArgumentException("index must reference at least one column");
        }
        for (Integer columnId : columnIds) {
            if (columnId == null || columnId < 1) {
                throw new IllegalArgumentException("columnIds must be >= 1");
            }
        }
    }

    public String name() {
        return name;
    }

    public List<Integer> columnIds() {
        return columnIds;
    }

    public boolean unique() {
        return unique;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IndexMetadata that)) {
            return false;
        }
        return name.equals(that.name)
                && columnIds.equals(that.columnIds)
                && unique == that.unique;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, columnIds, unique);
    }

    @Override
    public String toString() {
        return "IndexMetadata{name=" + name + ", columnIds=" + columnIds + ", unique=" + unique + "}";
    }
}
