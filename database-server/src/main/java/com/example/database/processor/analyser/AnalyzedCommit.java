package com.example.database.processor.analyser;

/**
 * Explicit {@code COMMIT} / {@code COMMIT TRANSACTION}.
 */
public record AnalyzedCommit() implements AnalyzedQuery {
}
