package com.example.database.processor.parser.ast;

/**
 * Column type keywords allowed in {@code CREATE TABLE} for Phase 1.
 * Analyzer maps these to {@code com.example.database.storage.catalog.ColumnType}.
 */
public enum ColumnSqlType {
    INT,
    VARCHAR,
    BOOLEAN
}
