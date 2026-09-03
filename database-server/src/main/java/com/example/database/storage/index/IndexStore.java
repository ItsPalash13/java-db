package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.page.Rid;

import java.util.Iterator;
import java.util.List;

/**
 * Owns on-disk B+ tree indexes ({@code .idx} files). Catalog definitions live in
 * {@link com.example.database.storage.catalog.CatalogManager}; this store owns
 * tree pages through the shared {@link com.example.database.storage.bufferpool.BufferPool}.
 */
public interface IndexStore {

    void createIndex(String database, String table, IndexMetadata index, ColumnType[] keyTypes);

    void dropIndex(String database, String table, String indexName);

    void dropTableIndexes(String database, String table, List<IndexMetadata> indexes);

    void insert(String database, String table, String indexName, Object[] key, Rid rid);

    void delete(String database, String table, String indexName, Object[] key, Rid rid);

    Iterator<Rid> lookupEquals(String database, String table, String indexName, Object[] key);

    /**
     * Keys in {@code [low, high]} per {@link IndexRange}, walking leaf siblings in sort order.
     * {@code null} low or high in the range means open on that side.
     */
    Iterator<Rid> lookupRange(String database, String table, String indexName, IndexRange range);
}
