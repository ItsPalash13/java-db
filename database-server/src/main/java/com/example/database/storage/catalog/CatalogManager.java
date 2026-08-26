package com.example.database.storage.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Owns table and schema metadata, plus database names (folders under the store root).
 * Does not own row data or index structures — those are {@code TableStore} and {@code IndexStore}.
 * Owns {@code CatalogStore} for persistence; callers never talk to the store or catalog.json.
 */
public interface CatalogManager {

    Optional<TableMetadata> getTable(String name);

    boolean tableExists(String name);

    /**
     * Registers a table and assigns table/column ids. Incoming ids on {@code table} are ignored.
     *
     * @throws CatalogException if the table name already exists, a column name is duplicated,
     *                          or the column list is empty
     */
    TableMetadata createTable(TableMetadata table);

    /** Insertion-order snapshot of in-memory tables. */
    List<TableMetadata> allTables();

    boolean databaseExists(String name);

    /** Insertion-order snapshot of database names. */
    List<String> allDatabases();

    /**
     * @throws CatalogException if the name already exists
     */
    void createDatabase(String name);

    /**
     * @throws CatalogException if the name is missing or the directory is not empty
     */
    void dropDatabase(String name);

    /**
     * Loads tables from the catalog store into memory, keeping stored ids,
     * and restores database names from directories.
     * Called from {@code StorageEngine.start()}, not from SQL.
     */
    void load();
}
