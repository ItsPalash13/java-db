package com.example.database.storage.index;

import java.util.Objects;

/**
 * Relative paths for on-disk B+ tree index files under the data directory.
 */
public final class IndexFiles {

    private IndexFiles() {
    }

    /** Index tree file, e.g. {@code shop/users/idx_users_id.idx}. */
    public static String idxPath(String database, String table, String indexName) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(indexName, "indexName");
        if (database.isBlank() || table.isBlank() || indexName.isBlank()) {
            throw new IllegalArgumentException("database, table, and indexName must not be blank");
        }
        return database + "/" + table + "/" + indexName + ".idx";
    }

    static String indexKey(String database, String table, String indexName) {
        return database + "." + table + "." + indexName;
    }
}
