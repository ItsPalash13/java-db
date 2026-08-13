package com.example.database.server;

import com.example.database.engine.QueryEngine;
import com.example.database.network.NetworkModule;

/**
 * Top-level server process coordinator.
 * Owns {@link NetworkModule} and {@link QueryEngine}; drives their lifecycles in the correct order.
 */
public final class DatabaseServer {

    private final NetworkModule networkModule;
    private final QueryEngine queryEngine;

    public DatabaseServer(NetworkModule networkModule, QueryEngine queryEngine) {
        this.networkModule = networkModule;
        this.queryEngine = queryEngine;
    }

    /**
     * Engine first so it is ready before any accepted connection can dispatch work;
     * then open the network.
     */
    public void start() {
        queryEngine.start();
        networkModule.start();
    }

    /**
     * Network first so accept/receive stop before the engine is torn down;
     * then stop the engine.
     */
    public void stop() {
        networkModule.stop();
        queryEngine.stop();
    }
}
