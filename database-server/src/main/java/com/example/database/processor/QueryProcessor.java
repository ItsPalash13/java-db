package com.example.database.processor;

import com.example.database.processor.executor.QueryResult;

/**
 * Query processor used by {@code DatabaseServer} (no lifecycle).
 * Lexes, parses, and executes decoded query strings.
 */
public interface QueryProcessor {

    /**
     * Run a decoded query string.
     *
     * @return structured result (status, error, or result set)
     */
    QueryResult execute(String query);

    /**
     * Plain-text outcome for tests and logging.
     */
    default String executeText(String query) {
        return execute(query).toResponse();
    }
}
