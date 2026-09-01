package com.example.database.storage.lock;

/** Granularity named by a {@link LockKey}. */
public enum LockLevel {
    CATALOG,
    DATABASE,
    TABLE,
    ROW
}
