package com.example.database.processor.planner;

/**
 * Statement kinds the planner/executor dispatch on.
 * Phase 1.6 only produces {@link #CREATE_TABLE}; other SQL stays {@link #UNRESOLVED}.
 */
public enum QueryType {
    CREATE_TABLE,
    CREATE_DATABASE,
    DROP_DATABASE,
    UNRESOLVED
}
