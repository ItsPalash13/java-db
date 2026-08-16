package com.example.database.storage;

/**
 * On-disk storage with its own lifecycle, owned by {@code DatabaseServer}.
 * Shared collaborator: query engine and other modules may use the same instance.
 * Started before the query engine accepts work; stopped after the query engine stops.
 * <p>
 * Stub: holds the store root ({@link DataDirectory}) only.
 */
public interface StorageEngine {

    /** Prepare storage resources (idempotent). */
    void start();

    /** Release storage resources (idempotent). */
    void stop();

    /** Store root this engine was constructed with. */
    DataDirectory dataDirectory();
}
