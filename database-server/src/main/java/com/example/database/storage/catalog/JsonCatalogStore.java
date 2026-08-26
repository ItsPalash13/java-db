package com.example.database.storage.catalog;

import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JSON catalog file at the store root; database names are directories.
 * {@link PhysicalStorage} only receives relative paths and bytes.
 */
public final class JsonCatalogStore implements CatalogStore {

    // Single file for Phase 1. Per-table metadata folders wait until TableStore exists.
    static final String CATALOG_FILE = "catalog.json";

    private final PhysicalStorage physicalStorage;

    public JsonCatalogStore(PhysicalStorage physicalStorage) {
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
    }

    @Override
    public List<TableMetadata> load() {
        try {
            if (!physicalStorage.exists(CATALOG_FILE)) {
                // First start has no catalog yet; memory starts empty.
                return List.of();
            }
            return CatalogJson.fromBytes(physicalStorage.read(CATALOG_FILE));
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to load catalog", e);
        }
    }

    @Override
    public void saveAll(List<TableMetadata> tables) {
        Objects.requireNonNull(tables, "tables");
        byte[] bytes = CatalogJson.toBytes(tables);
        try {
            writeCatalog(bytes);
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to save catalog", e);
        }
    }

    @Override
    public void saveTable(TableMetadata table) {
        Objects.requireNonNull(table, "table");
        List<TableMetadata> tables = new ArrayList<>(load());
        boolean replaced = false;
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name().equals(table.name())) {
                tables.set(i, table);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            tables.add(table);
        }
        saveAll(tables);
    }

    @Override
    public List<String> loadDatabases() {
        try {
            return physicalStorage.listDirectories("");
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to load databases", e);
        }
    }

    @Override
    public void createDatabase(String name) {
        Objects.requireNonNull(name, "name");
        try {
            physicalStorage.createDirectory(name);
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to create database: " + name, e);
        }
    }

    @Override
    public void dropDatabase(String name) {
        Objects.requireNonNull(name, "name");
        try {
            physicalStorage.deleteDirectory(name);
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to drop database: " + name, e);
        }
    }

    private void writeCatalog(byte[] bytes) {
        // PhysicalStorage.write() will not create a missing file; create once, then replace.
        if (!physicalStorage.exists(CATALOG_FILE)) {
            physicalStorage.create(CATALOG_FILE);
        }
        physicalStorage.write(CATALOG_FILE, bytes);
        physicalStorage.flush(CATALOG_FILE);
    }
}
