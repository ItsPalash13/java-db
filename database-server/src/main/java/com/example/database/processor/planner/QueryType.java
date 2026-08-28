package com.example.database.processor.planner;

/**
 * Statement kinds the planner/executor dispatch on.
 * CREATE/DROP TABLE and CREATE/DROP DATABASE are commands; other SQL stays {@link #UNRESOLVED}.
 */
public enum QueryType {
    CREATE_TABLE,
    DROP_TABLE,
    CREATE_DATABASE,
    DROP_DATABASE,
    ADD_COLUMN,
    DROP_COLUMN,
    CREATE_INDEX,
    DROP_INDEX,
    UNRESOLVED
}
