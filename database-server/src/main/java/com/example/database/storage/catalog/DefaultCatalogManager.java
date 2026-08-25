package com.example.database.storage.catalog;

import com.example.database.storage.physical.PhysicalStorage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory catalog. Owns {@link CatalogStore} when constructed with {@link PhysicalStorage}.
 * {@link #createTable} then rewrites the catalog file; {@link #load} fills memory on storage start.
 */
public final class DefaultCatalogManager implements CatalogManager {

    private final Map<String, TableMetadata> tablesByName = new LinkedHashMap<>();
    private final CatalogStore catalogStore;
    // Plain int on purpose. AtomicInteger would only make the counter race-free;
    // createTable also mutates the map and rewrites catalog.json. Concurrent DDL
    // is LockManager / Phase 2, which should lock the whole operation.
    private int nextTableId = 1;

    public DefaultCatalogManager() {
        this.catalogStore = null;
    }

    public DefaultCatalogManager(PhysicalStorage physicalStorage) {
        // Store is an internal persistence helper, not a StorageEngine peer.
        this.catalogStore = new JsonCatalogStore(Objects.requireNonNull(physicalStorage, "physicalStorage"));
    }

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
        int tableId = nextTableId;
        TableMetadata created = table.withAssignedIds(tableId, assignedColumns);
        tablesByName.put(name, created);
        nextTableId = tableId + 1;
        try {
            persist();
        } catch (RuntimeException e) {
            // Disk write failed: drop the in-memory table so memory and catalog.json stay aligned.
            tablesByName.remove(name);
            nextTableId = tableId;
            throw e;
        }
        return created;
    }

    @Override
    public List<TableMetadata> allTables() {
        return List.copyOf(tablesByName.values());
    }

    @Override
    public void load() {
        if (catalogStore == null) {
            return;
        }
        replaceAll(catalogStore.load());
    }

    void replaceAll(List<TableMetadata> tables) {
        Objects.requireNonNull(tables, "tables");
        tablesByName.clear();
        int maxTableId = 0;
        for (TableMetadata table : tables) {
            if (table.tableId().isEmpty()) {
                throw new CatalogException("restored table missing tableId: " + table.name());
            }
            if (tablesByName.containsKey(table.name())) {
                throw new CatalogException("duplicate table name: " + table.name());
            }
            tablesByName.put(table.name(), table);
            maxTableId = Math.max(maxTableId, table.tableId().getAsInt());
        }
        // Continue ids after the highest stored table so a new CREATE TABLE does not collide.
        nextTableId = maxTableId + 1;
    }

    private void persist() {
        if (catalogStore == null) {
            return;
        }
        catalogStore.saveAll(allTables());
    }
}
