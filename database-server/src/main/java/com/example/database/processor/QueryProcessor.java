package com.example.database.processor;

/**
 * Query processor used by {@code DatabaseServer} (no lifecycle).
 * Lexes, parses, and executes decoded query strings.
 */
public interface QueryProcessor {

    /**
     * Run a decoded query string.
     *
     * @return result or error response text
     */
    String execute(String query);
}
