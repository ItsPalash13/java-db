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
    // Plain int on purpose. Catalog exclusive LockManager serializes DDL that
    // mutates this counter together with the maps and catalog file write.
    private int nextTableId = 1;
    // Explicit BEGIN sessions defer catalog.json writes until COMMIT on this thread.
    private final ThreadLocal<Boolean> deferPersist = ThreadLocal.withInitial(() -> false);

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
    public TableMetadata addColumn(String database, String table, ColumnMetadata column) {
        Objects.requireNonNull(column, "column");
        requireFolderName(database, "database");
        requireFolderName(table, "table");
        TableMetadata existing = getTable(database, table).orElseThrow(
                () -> new CatalogException("table does not exist: " + database + "." + table)
        );
        for (ColumnMetadata existingColumn : existing.columns()) {
            if (existingColumn.name().equals(column.name())) {
                throw new CatalogException("duplicate column name: " + column.name());
            }
        }
        int nextColumnId = existing.columns().stream()
                .mapToInt(c -> c.columnId().orElseThrow())
                .max()
                .orElse(0) + 1;
        // Phase 1: nullable columns only; no row rewrite because there are no row files yet.
        ColumnMetadata assigned = column.withId(nextColumnId);
        List<ColumnMetadata> updatedColumns = new ArrayList<>(existing.columns());
        updatedColumns.add(assigned);
        TableMetadata updated = existing.withColumns(updatedColumns);
        tablesIn(database).put(table, updated);
        try {
            persistSaveTable(updated);
        } catch (RuntimeException e) {
            tablesIn(database).put(table, existing);
            throw e;
        }
        return updated;
    }

    @Override
    public TableMetadata dropColumn(String database, String table, String columnName) {
        requireFolderName(database, "database");
        requireFolderName(table, "table");
        Objects.requireNonNull(columnName, "column");
        TableMetadata existing = getTable(database, table).orElseThrow(
                () -> new CatalogException("table does not exist: " + database + "." + table)
        );
        ColumnMetadata target = null;
        for (ColumnMetadata column : existing.columns()) {
            if (column.name().equals(columnName)) {
                target = column;
                break;
            }
        }
        if (target == null) {
            throw new CatalogException("column does not exist: " + columnName);
        }
        if (existing.columns().size() <= 1) {
            throw new CatalogException("cannot drop last column: " + columnName);
        }
        int targetColumnId = target.columnId().orElseThrow();
        for (IndexMetadata index : existing.indexes()) {
            if (index.columnIds().contains(targetColumnId)) {
                throw new CatalogException("index references column: " + index.name());
            }
        }
        List<ColumnMetadata> updatedColumns = new ArrayList<>();
        for (ColumnMetadata column : existing.columns()) {
            if (!column.name().equals(columnName)) {
                updatedColumns.add(column);
            }
        }
        // Phase 1: catalog-only; remaining column ids stay as-is because no row files exist yet.
        TableMetadata updated = existing.withColumns(updatedColumns);
        tablesIn(database).put(table, updated);
        try {
            persistSaveTable(updated);
        } catch (RuntimeException e) {
            tablesIn(database).put(table, existing);
            throw e;
        }
        return updated;
    }

    @Override
    public TableMetadata createIndex(String database, String table, IndexMetadata index) {
        Objects.requireNonNull(index, "index");
        requireFolderName(database, "database");
        requireFolderName(table, "table");
        TableMetadata existing = getTable(database, table).orElseThrow(
                () -> new CatalogException("table does not exist: " + database + "." + table)
        );
        for (IndexMetadata existingIndex : existing.indexes()) {
            if (existingIndex.name().equals(index.name())) {
                throw new CatalogException("index already exists: " + index.name());
            }
        }
        Set<Integer> tableColumnIds = new HashSet<>();
        for (ColumnMetadata column : existing.columns()) {
            tableColumnIds.add(column.columnId().orElseThrow());
        }
        for (Integer columnId : index.columnIds()) {
            if (!tableColumnIds.contains(columnId)) {
                throw new CatalogException("index references unknown column id: " + columnId);
            }
        }
        List<IndexMetadata> updatedIndexes = new ArrayList<>(existing.indexes());
        updatedIndexes.add(index);
        TableMetadata updated = existing.withIndexes(updatedIndexes);
        tablesIn(database).put(table, updated);
        try {
            persistSaveTable(updated);
        } catch (RuntimeException e) {
            tablesIn(database).put(table, existing);
            throw e;
        }
        return updated;
    }

    @Override
    public void dropIndex(String indexName) {
        Objects.requireNonNull(indexName, "indexName");
        if (indexName.isBlank()) {
            throw new CatalogException("index name must not be blank");
        }
        String foundDatabase = null;
        String foundTable = null;
        TableMetadata foundMetadata = null;
        for (TableMetadata table : allTables()) {
            for (IndexMetadata index : table.indexes()) {
                if (!index.name().equals(indexName)) {
                    continue;
                }
                if (foundMetadata != null) {
                    throw new CatalogException("ambiguous index name: " + indexName);
                }
                foundDatabase = table.database();
                foundTable = table.name();
                foundMetadata = table;
            }
        }
        if (foundMetadata == null) {
            throw new CatalogException("index does not exist: " + indexName);
        }
        List<IndexMetadata> remaining = new ArrayList<>();
        for (IndexMetadata index : foundMetadata.indexes()) {
            if (!index.name().equals(indexName)) {
                remaining.add(index);
            }
        }
        TableMetadata updated = foundMetadata.withIndexes(remaining);
        tablesIn(foundDatabase).put(foundTable, updated);
        try {
            persistSaveTable(updated);
        } catch (RuntimeException e) {
            tablesIn(foundDatabase).put(foundTable, foundMetadata);
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

    @Override
    public void setDeferPersist(boolean defer) {
        deferPersist.set(defer);
    }

    @Override
    public CatalogSnapshot snapshot() {
        return new CatalogSnapshot(tablesByDatabase, databaseNames, nextTableId);
    }

    @Override
    public void restoreSnapshot(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        tablesByDatabase.clear();
        tablesByDatabase.putAll(deepCopyTables(snapshot.tablesByDatabase()));
        databaseNames.clear();
        databaseNames.addAll(snapshot.databaseNames());
        nextTableId = snapshot.nextTableId();
    }

    @Override
    public void persistChangesSince(CatalogSnapshot before) {
        Objects.requireNonNull(before, "before");
        if (catalogStore == null) {
            return;
        }
        for (String database : databaseNames) {
            if (!before.databaseNames().contains(database)) {
                persistCreateDatabase(database);
            }
        }
        for (String database : before.databaseNames()) {
            if (!databaseNames.contains(database)) {
                persistDropDatabase(database);
            }
        }
        for (TableMetadata table : allTables()) {
            TableMetadata previous = findInSnapshot(before, table.database(), table.name());
            if (previous == null || !previous.equals(table)) {
                persistSaveTable(table);
            }
        }
        for (TableMetadata table : before.allTables()) {
            if (!tableExists(table.database(), table.name())) {
                persistDropTable(table.database(), table.name());
            }
        }
    }

    private static TableMetadata findInSnapshot(CatalogSnapshot snapshot, String database, String table) {
        Map<String, TableMetadata> tables = snapshot.tablesByDatabase().get(database);
        if (tables == null) {
            return null;
        }
        return tables.get(table);
    }

    private static Map<String, Map<String, TableMetadata>> deepCopyTables(
            Map<String, Map<String, TableMetadata>> source
    ) {
        Map<String, Map<String, TableMetadata>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, TableMetadata>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
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
        if (catalogStore == null || deferPersist.get()) {
            return;
        }
        catalogStore.saveTable(table);
    }

    private void persistDropTable(String database, String table) {
        if (catalogStore == null || deferPersist.get()) {
            return;
        }
        catalogStore.dropTable(database, table);
    }

    private void persistCreateDatabase(String name) {
        if (catalogStore == null || deferPersist.get()) {
            return;
        }
        catalogStore.createDatabase(name);
    }

    private void persistDropDatabase(String name) {
        if (catalogStore == null || deferPersist.get()) {
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
