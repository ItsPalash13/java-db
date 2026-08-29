package com.example.database.processor.analyser;

/**
 * Explicit {@code ROLLBACK} / {@code ROLLBACK TRANSACTION}.
 */
public record AnalyzedRollback() implements AnalyzedQuery {
}
