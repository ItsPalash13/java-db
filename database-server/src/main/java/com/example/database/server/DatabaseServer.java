package com.example.database.server;

import com.example.database.network.NetworkModule;
import com.example.database.processor.QueryProcessor;
import com.example.database.storage.StorageEngine;

/**
 * Top-level server process coordinator.
 * Owns {@link StorageEngine} and {@link NetworkModule} lifecycles.
 * Uses {@link QueryProcessor} (no lifecycle) to run queries via the network stack.
 */
public final class DatabaseServer {

    private final StorageEngine storageEngine;
    private final NetworkModule networkModule;
    private final QueryProcessor queryProcessor;

    public DatabaseServer(
            StorageEngine storageEngine,
            NetworkModule networkModule,
            QueryProcessor queryProcessor
    ) {
        this.storageEngine = storageEngine;
        this.networkModule = networkModule;
        this.queryProcessor = queryProcessor;
    }

    /**
     * Storage first so the store is ready, then open the network.
     * Query processor has no start/stop — it is used when requests arrive.
     */
    public void start() {
        storageEngine.start();
        networkModule.start();
    }

    /**
     * Network first so accept/receive stop, then storage.
     */
    public void stop() {
        networkModule.stop();
        storageEngine.stop();
    }
}
