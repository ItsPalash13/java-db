package com.example.database.processor.analyser;

/**
 * {@code DESCRIBE shop.table} — table must exist.
 */
public record AnalyzedDescribeTable(String database, String table) implements AnalyzedQuery {
}
