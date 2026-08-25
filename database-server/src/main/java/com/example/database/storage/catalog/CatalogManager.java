package com.example.database.storage.catalog;

import java.util.Optional;

/**
 * Owns table and schema metadata for the single Phase 1 database.
 * Does not own row data or index structures — those are {@code TableStore} and {@code IndexStore}.
 * <p>
 * In-memory only in 1.1. Persistence ({@code CatalogStore}) comes later.
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
}
