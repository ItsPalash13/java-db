package com.example.database.storage.catalog;

/**
 * Owns table and schema metadata: databases, tables, columns, index definitions.
 * Does not own row data or index structures — those are {@code TableStore} and {@code IndexStore}.
 * <p>
 * Loaded from disk into memory on storage start. Persistence goes through the storage stack
 * (buffer pool / physical storage), not a separate catalog disk path.
 * <p>
 * Planned surface (not implemented):
 * <ul>
 *   <li>{@code getDatabase(name)}</li>
 *   <li>{@code getTable(dbName, name)}</li>
 *   <li>{@code getIndexes(table)}</li>
 *   <li>{@code createTable(metadata)}</li>
 *   <li>{@code dropTable(name)}</li>
 *   <li>{@code addIndex(metadata)}</li>
 * </ul>
 */
public interface CatalogManager {
}
