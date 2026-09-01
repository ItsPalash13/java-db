package com.example.database.storage.lock;

import java.util.Objects;

/**
 * Names one lockable object. Mode and duration are chosen at acquire time, not stored here.
 */
public record LockKey(LockLevel level, String database, String table, Long rowId) {

    public LockKey {
        Objects.requireNonNull(level, "level");
        database = database == null ? "" : database;
        table = table == null ? "" : table;
    }

    public static LockKey catalog() {
        return new LockKey(LockLevel.CATALOG, "", "", null);
    }

    public static LockKey database(String database) {
        Objects.requireNonNull(database, "database");
        return new LockKey(LockLevel.DATABASE, database, "", null);
    }

    public static LockKey table(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        return new LockKey(LockLevel.TABLE, database, table, null);
    }

    public static LockKey row(String database, String table, long rowId) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        return new LockKey(LockLevel.ROW, database, table, rowId);
    }
}
