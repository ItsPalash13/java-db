package com.example.database.storage.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory catalog. Assigns sequential table and column ids starting at 1.
 */
public final class DefaultCatalogManager implements CatalogManager {

    private final Map<String, TableMetadata> tablesByName = new LinkedHashMap<>();
    private int nextTableId = 1;

    @Override
    public Optional<TableMetadata> getTable(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(tablesByName.get(name));
    }

    @Override
    public boolean tableExists(String name) {
        Objects.requireNonNull(name, "name");
        return tablesByName.containsKey(name);
    }

    @Override
    public TableMetadata createTable(TableMetadata table) {
        Objects.requireNonNull(table, "table");
        String name = table.name();
        if (tablesByName.containsKey(name)) {
            throw new CatalogException("table already exists: " + name);
        }
        List<ColumnMetadata> columns = table.columns();
        if (columns.isEmpty()) {
            throw new CatalogException("table must have at least one column: " + name);
        }
        Set<String> seen = new HashSet<>();
        List<ColumnMetadata> assignedColumns = new ArrayList<>(columns.size());
        int columnId = 1;
        for (ColumnMetadata column : columns) {
            if (!seen.add(column.name())) {
                throw new CatalogException("duplicate column name: " + column.name());
            }
            assignedColumns.add(column.withId(columnId++));
        }
        TableMetadata created = table.withAssignedIds(nextTableId++, assignedColumns);
        tablesByName.put(name, created);
        return created;
    }
}
