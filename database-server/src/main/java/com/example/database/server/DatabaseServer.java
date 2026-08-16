package com.example.database.server;

import com.example.database.engine.QueryEngine;
import com.example.database.network.NetworkModule;
import com.example.database.storage.StorageEngine;

/**
 * Top-level server process coordinator.
 * Owns {@link StorageEngine}, {@link NetworkModule}, and {@link QueryEngine};
 * drives their lifecycles in the correct order.
 */
public final class DatabaseServer {

    private final StorageEngine storageEngine;
    private final NetworkModule networkModule;
    private final QueryEngine queryEngine;

    public DatabaseServer(
            StorageEngine storageEngine,
            NetworkModule networkModule,
            QueryEngine queryEngine
    ) {
        this.storageEngine = storageEngine;
        this.networkModule = networkModule;
        this.queryEngine = queryEngine;
    }

    /**
     * Storage first so the store is ready, then the query engine, then the network.
     */
    public void start() {
        storageEngine.start();
        queryEngine.start();
        networkModule.start();
    }

    /**
     * Network first so accept/receive stop, then the query engine, then storage.
     */
    public void stop() {
        networkModule.stop();
        queryEngine.stop();
        storageEngine.stop();
    }
}
