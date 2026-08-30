package com.example.database.processor.analyser;

/**
 * Explicit {@code CHECKPOINT} — durable WAL barrier + truncate of absorbed redo.
 * Analyser has nothing to validate against the catalog; planning is a straight map
 * to {@code CheckpointPlan}.
 */
public record AnalyzedCheckpoint() implements AnalyzedQuery {
}
