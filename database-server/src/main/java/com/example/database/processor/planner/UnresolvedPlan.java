package com.example.database.processor.planner;

import com.example.database.processor.analyser.UnresolvedQuery;

import java.util.Objects;

/**
 * Analyzed but not planned yet. Remaining catch-all; DML/DQL has typed plans.
 */
public final class UnresolvedPlan implements ExecutionPlan {

    private final UnresolvedQuery source;

    public UnresolvedPlan(UnresolvedQuery source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public QueryType queryType() {
        return QueryType.UNRESOLVED;
    }

    public UnresolvedQuery source() {
        return source;
    }

    @Override
    public String toString() {
        return "UnresolvedPlan{source=" + source + "}";
    }
}
