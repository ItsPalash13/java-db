package com.example.database.processor.analyser;

/**
 * {@code SHOW TABLES FROM shop} — database must exist.
 */
public record AnalyzedShowTables(String database) implements AnalyzedQuery {
}
