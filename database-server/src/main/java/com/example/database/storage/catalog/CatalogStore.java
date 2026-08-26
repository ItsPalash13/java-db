package com.example.database.storage.catalog;

import java.util.List;

/**
 * Persistence helper used only by {@code CatalogManager}. Knows JSON, catalog.json, and database folders.
 * {@code PhysicalStorage} only sees relative paths and bytes.
 */
public interface CatalogStore {

    /**
     * Tables previously saved, or empty if the catalog file is missing.
     * Loaded tables keep their stored ids.
     */
    List<TableMetadata> load();

    /** Rewrites the whole catalog file. Acceptable while the catalog is small. */
    void saveAll(List<TableMetadata> tables);

    /** Upserts one table in the catalog file (load, replace by name, rewrite). */
    void saveTable(TableMetadata table);

    /** Immediate database directory names under the store root. */
    List<String> loadDatabases();

    /** Creates {@code data/<name>/}. */
    void createDatabase(String name);

    /** Deletes empty {@code data/<name>/}. */
    void dropDatabase(String name);
}
