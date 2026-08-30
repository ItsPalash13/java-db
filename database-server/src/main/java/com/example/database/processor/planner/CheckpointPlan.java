package com.example.database.processor.planner;

/**
 * Planned {@code CHECKPOINT}. Dispatched to {@code CheckpointExecutor}, not
 * {@code CommandExecutor} — no catalog mutation and no implicit transaction wrapper.
 */
public record CheckpointPlan() implements ExecutionPlan {
    @Override
    public QueryType queryType() {
        return QueryType.CHECKPOINT;
    }
}
