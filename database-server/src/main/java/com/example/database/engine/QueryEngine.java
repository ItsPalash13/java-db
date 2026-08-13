package com.example.database.engine;

/**
 * Executes decoded queries.
 */
public interface QueryEngine {

    String execute(String query);
}
