package com.example.database.storage.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Owns table and schema metadata, plus database names (folders under the store root).
 * Does not own row data or index structures — those are {@code TableStore} and {@code IndexStore}.
 * Owns {@code CatalogStore} for persistence; callers never talk to the store or catalog.json.
 */
public interface CatalogManager {

    Optional<TableMetadata> getTable(String database, String table);

    boolean tableExists(String database, String table);

    /**
     * Registers a table and assigns table/column ids. Incoming ids on {@code table} are ignored.
     *
     * @throws CatalogException if the database is missing, the table name already exists in that
     *                          database, a column name is duplicated, or the column list is empty
     */
    TableMetadata createTable(TableMetadata table);

    /**
     * @throws CatalogException if the database or table is missing
     */
    void dropTable(String database, String table);

    /**
     * Appends a nullable column with the next column id and rewrites the table catalog file.
     *
     * @throws CatalogException if the database or table is missing, or the column name already exists
     */
    TableMetadata addColumn(String database, String table, ColumnMetadata column);

    /**
     * Removes a column by name and rewrites the table catalog file. Column ids on remaining
     * columns are unchanged — there are no row files to rewrite in Phase 1.
     *
     * @throws CatalogException if the database or table is missing, the column is missing,
     *                          it is the last column, or an index still references the column
     */
    TableMetadata dropColumn(String database, String table, String column);

    /**
     * Adds a catalog-only index definition and rewrites the table catalog file.
     *
     * @throws CatalogException if the table is missing, the index name already exists on that table,
     *                          or a column id is not on the table
     */
    TableMetadata createIndex(String database, String table, IndexMetadata index);

    /**
     * Removes an index definition by name. {@code DROP INDEX name} has no table qualifier, so the
     * name must be unique across the whole catalog.
     *
     * @throws CatalogException if the index is missing or ambiguous
     */
    void dropIndex(String index);

    /** Insertion-order snapshot of in-memory tables across all databases. */
    List<TableMetadata> allTables();

    boolean databaseExists(String name);

    /** Insertion-order snapshot of database names. */
    List<String> allDatabases();

    /**
     * @throws CatalogException if the name already exists
     */
    void createDatabase(String name);

    /**
     * @throws CatalogException if the name is missing, the database still has tables,
     *                          or the directory is not empty
     */
    void dropDatabase(String name);

    /**
     * Loads tables from per-table catalog files into memory, keeping stored ids,
     * and restores database names from directories.
     * Called from {@code StorageEngine.start()}, not from SQL.
     */
    void load();
}
