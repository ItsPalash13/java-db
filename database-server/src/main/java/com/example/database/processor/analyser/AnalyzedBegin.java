package com.example.database.processor.analyser;

/**
 * Explicit {@code BEGIN} / {@code BEGIN TRANSACTION}.
 */
public record AnalyzedBegin() implements AnalyzedQuery {
}
