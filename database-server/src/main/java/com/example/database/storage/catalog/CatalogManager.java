package com.example.database.storage.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Owns table and schema metadata for the single Phase 1 database.
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

    /**
     * Loads tables from the catalog store into memory, keeping stored ids.
     * Called from {@code StorageEngine.start()}, not from SQL.
     */
    void load();
}
