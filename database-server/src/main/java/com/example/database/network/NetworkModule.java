package com.example.database.network;

/**
 * Transport-agnostic network facade owned by {@code DatabaseServer}.
 * Implementations accept clients and dispatch work; they must not expose {@code java.net} types.
 */
public interface NetworkModule {

    /** Begin accepting connections (idempotent). */
    void start();

    /** Stop accepting and release transport resources (idempotent). */
    void stop();
}
