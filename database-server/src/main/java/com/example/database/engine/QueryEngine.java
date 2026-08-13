package com.example.database.engine;

/**
 * Query execution engine with its own lifecycle, owned by {@code DatabaseServer}.
 * Started before the network accepts traffic; stopped after the network shuts down.
 */
public interface QueryEngine {

    /** Prepare engine resources (idempotent). */
    void start();

    /** Release engine resources (idempotent). */
    void stop();

    /**
     * Run a decoded query string.
     *
     * @throws IllegalStateException if called while not started
     */
    String execute(String query);
}
