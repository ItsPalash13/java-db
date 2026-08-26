package com.example.database.storage.catalog;

import com.example.database.storage.physical.PhysicalStorage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory catalog. Owns {@link CatalogStore} when constructed with {@link PhysicalStorage}.
 * {@link #createTable} rewrites catalog.json; {@link #createDatabase} creates a folder.
 * {@link #load} fills memory on storage start.
 */
public final class DefaultCatalogManager implements CatalogManager {

    private final Map<String, TableMetadata> tablesByName = new LinkedHashMap<>();
    private final Set<String> databaseNames = new LinkedHashSet<>();
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
    public boolean databaseExists(String name) {
        Objects.requireNonNull(name, "name");
        return databaseNames.contains(name);
    }

    @Override
    public List<String> allDatabases() {
        return List.copyOf(databaseNames);
    }

    @Override
    public void createDatabase(String name) {
        requireDatabaseName(name);
        if (databaseNames.contains(name)) {
            throw new CatalogException("database already exists: " + name);
        }
        databaseNames.add(name);
        try {
            persistCreateDatabase(name);
        } catch (RuntimeException e) {
            databaseNames.remove(name);
            throw e;
        }
    }

    @Override
    public void dropDatabase(String name) {
        requireDatabaseName(name);
        if (!databaseNames.contains(name)) {
            throw new CatalogException("database does not exist: " + name);
        }
        databaseNames.remove(name);
        try {
            persistDropDatabase(name);
        } catch (RuntimeException e) {
            databaseNames.add(name);
            throw e;
        }
    }

    @Override
    public void load() {
        if (catalogStore == null) {
            return;
        }
        replaceAll(catalogStore.load());
        databaseNames.clear();
        databaseNames.addAll(catalogStore.loadDatabases());
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

    private void persistCreateDatabase(String name) {
        if (catalogStore == null) {
            return;
        }
        catalogStore.createDatabase(name);
    }

    private void persistDropDatabase(String name) {
        if (catalogStore == null) {
            return;
        }
        catalogStore.dropDatabase(name);
    }

    private static void requireDatabaseName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new CatalogException("database name must not be blank");
        }
        // CatalogStore uses the name as a relative path; reject separators so CREATE
        // cannot write outside a single folder under the store root.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new CatalogException("invalid database name: " + name);
        }
    }
}
