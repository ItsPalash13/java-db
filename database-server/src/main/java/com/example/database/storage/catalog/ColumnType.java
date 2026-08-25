package com.example.database.storage.catalog;

/**
 * Supported column types for Phase 1 catalog metadata.
 * Matches the literal kinds the lexer already recognizes (number, string, boolean).
 */
public enum ColumnType {
    INT,
    VARCHAR,
    BOOLEAN
}
