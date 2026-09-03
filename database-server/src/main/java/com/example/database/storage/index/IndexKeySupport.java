package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Resolves catalog index column ids to key types and row value slices for B+ tree maintenance.
 */
public final class IndexKeySupport {

    private IndexKeySupport() {
    }

    public static ColumnType[] keyTypes(TableMetadata table, IndexMetadata index) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(index, "index");
        return index.columnIds().stream()
                .map(id -> columnById(table.columns(), id).type())
                .toArray(ColumnType[]::new);
    }

    public static ColumnType[] keyTypes(TableMetadata table, List<Integer> columnIds) {
        return columnIds.stream()
                .map(id -> columnById(table.columns(), id).type())
                .toArray(ColumnType[]::new);
    }

    public static Object[] keyValues(TableMetadata table, IndexMetadata index, Object[] rowValues) {
        return index.columnIds().stream()
                .map(id -> rowValues[columnById(table.columns(), id).columnId().getAsInt() - 1])
                .toArray();
    }

    private static ColumnMetadata columnById(List<ColumnMetadata> columns, int columnId) {
        for (ColumnMetadata column : columns) {
            if (column.columnId().isPresent() && column.columnId().getAsInt() == columnId) {
                return column;
            }
        }
        throw new IllegalStateException("column id not in table: " + columnId);
    }
}
