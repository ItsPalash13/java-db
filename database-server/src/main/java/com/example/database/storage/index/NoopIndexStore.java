package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.page.Rid;

import java.util.Iterator;
import java.util.List;

/**
 * Inert {@link IndexStore} for unit tests that exercise DDL/DML without on-disk trees.
 */
public final class NoopIndexStore implements IndexStore {

    @Override
    public void createIndex(String database, String table, IndexMetadata index, ColumnType[] keyTypes) {
    }

    @Override
    public void dropIndex(String database, String table, String indexName) {
    }

    @Override
    public void dropTableIndexes(String database, String table, List<IndexMetadata> indexes) {
    }

    @Override
    public void insert(String database, String table, String indexName, Object[] key, Rid rid) {
    }

    @Override
    public void delete(String database, String table, String indexName, Object[] key, Rid rid) {
    }

    @Override
    public Iterator<Rid> lookupEquals(String database, String table, String indexName, Object[] key) {
        return List.<Rid>of().iterator();
    }

    @Override
    public Iterator<Rid> lookupRange(String database, String table, String indexName, IndexRange range) {
        return List.<Rid>of().iterator();
    }
}
