package com.example.database.storage.table;

import java.util.Objects;

/**
 * Relative paths for on-disk table heaps under the data directory.
 * One {@code .ibd} file per table; catalog JSON stays alongside it in the same folder.
 */
public final class TableHeapFiles {

    private TableHeapFiles() {
    }

    /**
     * Heap file for {@code database.table}, e.g. {@code shop/users/users.ibd}.
     * Page offset I/O uses {@code pageId * pageSize} within this file only.
     */
    public static String ibdPath(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        if (database.isBlank() || table.isBlank()) {
            throw new IllegalArgumentException("database and table must not be blank");
        }
        return database + "/" + table + "/" + table + ".ibd";
    }

    static String tableKey(String database, String table) {
        return database + "." + table;
    }
}
