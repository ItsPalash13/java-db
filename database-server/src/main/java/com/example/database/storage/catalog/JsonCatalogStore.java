package com.example.database.storage.catalog;

import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-table JSON at {@code <database>/<table>/catalog.json}. Database names are directories.
 * {@link PhysicalStorage} only receives relative paths and bytes.
 */
public final class JsonCatalogStore implements CatalogStore {

    static final String CATALOG_FILE = "catalog.json";

    private final PhysicalStorage physicalStorage;

    public JsonCatalogStore(PhysicalStorage physicalStorage) {
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
    }

    @Override
    public List<TableMetadata> load() {
        try {
            List<TableMetadata> tables = new ArrayList<>();
            for (String database : physicalStorage.listDirectories("")) {
                for (String table : physicalStorage.listDirectories(database)) {
                    String file = catalogPath(database, table);
                    if (!physicalStorage.exists(file)) {
                        // Empty table folders are not catalog entries.
                        continue;
                    }
                    TableMetadata loaded = CatalogJson.fromBytes(physicalStorage.read(file), database);
                    if (!loaded.name().equals(table)) {
                        throw new CatalogException(
                                "catalog table name '" + loaded.name()
                                        + "' does not match folder " + database + "/" + table
                        );
                    }
                    tables.add(loaded);
                }
            }
            return List.copyOf(tables);
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to load catalog", e);
        }
    }

    @Override
    public void saveTable(TableMetadata table) {
        Objects.requireNonNull(table, "table");
        byte[] bytes = CatalogJson.toBytes(table);
        String directory = tableDir(table.database(), table.name());
        String file = catalogPath(table.database(), table.name());
        try {
            // create() will not make parent folders; CatalogStore owns that layout.
            physicalStorage.createDirectory(directory);
            if (!physicalStorage.exists(file)) {
                physicalStorage.create(file);
            }
            physicalStorage.write(file, bytes);
            physicalStorage.flush(file);
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to save table " + table.qualifiedName(), e);
        }
    }

    @Override
    public void dropTable(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        String file = catalogPath(database, table);
        String directory = tableDir(database, table);
        try {
            if (physicalStorage.exists(file)) {
                physicalStorage.delete(file);
            }
            if (physicalStorage.exists(directory)) {
                physicalStorage.deleteDirectory(directory);
            }
        } catch (PhysicalStorageException e) {
            throw new CatalogException("failed to drop table " + database + "." + table, e);
        }
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

    private static String tableDir(String database, String table) {
        return database + "/" + table;
    }

    private static String catalogPath(String database, String table) {
        return tableDir(database, table) + "/" + CATALOG_FILE;
    }
}
