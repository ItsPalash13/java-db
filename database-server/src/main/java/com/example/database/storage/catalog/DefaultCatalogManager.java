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
 * {@link #createTable} writes {@code db/table/catalog.json}; {@link #createDatabase} creates a folder.
 * {@link #load} fills memory on storage start.
 */
public final class DefaultCatalogManager implements CatalogManager {

    private final Map<String, Map<String, TableMetadata>> tablesByDatabase = new LinkedHashMap<>();
    private final Set<String> databaseNames = new LinkedHashSet<>();
    private final CatalogStore catalogStore;
    // Plain int on purpose. AtomicInteger would only make the counter race-free;
    // createTable also mutates the map and rewrites a catalog file. Concurrent DDL
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
    public Optional<TableMetadata> getTable(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        Map<String, TableMetadata> tables = tablesByDatabase.get(database);
        if (tables == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tables.get(table));
    }

    @Override
    public boolean tableExists(String database, String table) {
        return getTable(database, table).isPresent();
    }

    @Override
    public TableMetadata createTable(TableMetadata table) {
        Objects.requireNonNull(table, "table");
        String database = table.database();
        String name = table.name();
        requireFolderName(database, "database");
        requireFolderName(name, "table");
        if (!databaseNames.contains(database)) {
            throw new CatalogException("database does not exist: " + database);
        }
        if (tableExists(database, name)) {
            throw new CatalogException("table already exists: " + table.qualifiedName());
        }
        List<ColumnMetadata> columns = table.columns();
        if (columns.isEmpty()) {
            throw new CatalogException("table must have at least one column: " + table.qualifiedName());
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
        tablesIn(database).put(name, created);
        nextTableId = tableId + 1;
        try {
            persistSaveTable(created);
        } catch (RuntimeException e) {
            // Disk write failed: drop the in-memory table so memory and files stay aligned.
            removeTableFromMemory(database, name);
            nextTableId = tableId;
            if (catalogStore != null) {
                try {
                    catalogStore.dropTable(database, name);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of a partial shop/users/ directory.
                }
            }
            throw e;
        }
        return created;
    }

    @Override
    public void dropTable(String database, String table) {
        requireFolderName(database, "database");
        requireFolderName(table, "table");
        TableMetadata existing = getTable(database, table).orElseThrow(
                () -> new CatalogException("table does not exist: " + database + "." + table)
        );
        removeTableFromMemory(database, table);
        try {
            persistDropTable(database, table);
        } catch (RuntimeException e) {
            tablesIn(database).put(table, existing);
            throw e;
        }
    }

    @Override
    public List<TableMetadata> allTables() {
        List<TableMetadata> tables = new ArrayList<>();
        for (Map<String, TableMetadata> perDatabase : tablesByDatabase.values()) {
            tables.addAll(perDatabase.values());
        }
        return List.copyOf(tables);
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
        requireFolderName(name, "database");
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
        requireFolderName(name, "database");
        if (!databaseNames.contains(name)) {
            throw new CatalogException("database does not exist: " + name);
        }
        Map<String, TableMetadata> tables = tablesByDatabase.get(name);
        if (tables != null && !tables.isEmpty()) {
            throw new CatalogException("database is not empty: " + name);
        }
        databaseNames.remove(name);
        tablesByDatabase.remove(name);
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
        tablesByDatabase.clear();
        databaseNames.clear();
        databaseNames.addAll(catalogStore.loadDatabases());
        replaceAll(catalogStore.load());
    }

    void replaceAll(List<TableMetadata> tables) {
        Objects.requireNonNull(tables, "tables");
        tablesByDatabase.clear();
        int maxTableId = 0;
        for (TableMetadata table : tables) {
            if (table.tableId().isEmpty()) {
                throw new CatalogException("restored table missing tableId: " + table.qualifiedName());
            }
            if (tableExists(table.database(), table.name())) {
                throw new CatalogException("duplicate table name: " + table.qualifiedName());
            }
            tablesIn(table.database()).put(table.name(), table);
            maxTableId = Math.max(maxTableId, table.tableId().getAsInt());
        }
        // Continue ids after the highest stored table so a new CREATE TABLE does not collide.
        nextTableId = maxTableId + 1;
    }

    private Map<String, TableMetadata> tablesIn(String database) {
        return tablesByDatabase.computeIfAbsent(database, key -> new LinkedHashMap<>());
    }

    private void removeTableFromMemory(String database, String table) {
        Map<String, TableMetadata> tables = tablesByDatabase.get(database);
        if (tables == null) {
            return;
        }
        tables.remove(table);
        if (tables.isEmpty()) {
            tablesByDatabase.remove(database);
        }
    }

    private void persistSaveTable(TableMetadata table) {
        if (catalogStore == null) {
            return;
        }
        catalogStore.saveTable(table);
    }

    private void persistDropTable(String database, String table) {
        if (catalogStore == null) {
            return;
        }
        catalogStore.dropTable(database, table);
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

    private static void requireFolderName(String name, String kind) {
        Objects.requireNonNull(name, kind);
        if (name.isBlank()) {
            throw new CatalogException(kind + " name must not be blank");
        }
        // CatalogStore uses the name as a relative path segment; reject separators so CREATE
        // cannot write outside a single folder under the store root.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new CatalogException("invalid " + kind + " name: " + name);
        }
    }
}
