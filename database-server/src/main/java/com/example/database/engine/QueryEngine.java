package com.example.database.engine;

/**
 * Executes decoded queries and owns its own start/stop lifecycle.
 */
public interface QueryEngine {

    void start();

    void stop();

    String execute(String query);
}
