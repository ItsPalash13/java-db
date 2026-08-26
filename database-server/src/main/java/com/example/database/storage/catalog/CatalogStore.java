package com.example.database.storage.catalog;

import java.util.List;

/**
 * Persistence helper used only by {@code CatalogManager}. Knows JSON and {@code db/table/catalog.json}.
 * {@code PhysicalStorage} only sees relative paths and bytes.
 */
public interface CatalogStore {

    /**
     * Tables previously saved under each database folder, or empty if none exist.
     * Loaded tables keep their stored ids. Database name comes from the folder.
     */
    List<TableMetadata> load();

    /** Writes one table file. Creates {@code db/table/} if needed. */
    void saveTable(TableMetadata table);

    /** Deletes {@code db/table/catalog.json} and the empty table directory. */
    void dropTable(String database, String table);

    /** Immediate database directory names under the store root. */
    List<String> loadDatabases();

    /** Creates {@code data/<name>/}. */
    void createDatabase(String name);

    /** Deletes empty {@code data/<name>/}. */
    void dropDatabase(String name);
}
