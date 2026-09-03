package com.example.database.storage.lock;

/** Granularity named by a {@link LockKey}. */
public enum LockLevel {
    /** Whole engine — CHECKPOINT X vs DML/DQL/DDL IS/IX. */
    ENGINE,
    CATALOG,
    DATABASE,
    TABLE,
    ROW
}
