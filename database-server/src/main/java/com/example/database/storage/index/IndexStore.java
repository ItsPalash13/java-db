package com.example.database.storage.index;

/**
 * Owns index structures (the trees / files used to look up rows).
 * Index <em>definitions</em> (name, columns, unique) live in {@code CatalogManager};
 * this store owns how those indexes are accessed on disk.
 */
public interface IndexStore {
}
