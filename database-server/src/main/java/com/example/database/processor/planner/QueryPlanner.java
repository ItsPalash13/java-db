package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedQuery;

/**
 * Turns analyzed SQL into an {@link ExecutionPlan}. No catalog writes and no file layout.
 */
public interface QueryPlanner {

    ExecutionPlan plan(AnalyzedQuery analyzed);
}
