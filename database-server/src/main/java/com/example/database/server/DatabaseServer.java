package com.example.database.server;

import com.example.database.engine.QueryEngine;
import com.example.database.network.NetworkModule;

public final class DatabaseServer {

    private final NetworkModule networkModule;
    private final QueryEngine queryEngine;

    public DatabaseServer(NetworkModule networkModule, QueryEngine queryEngine) {
        this.networkModule = networkModule;
        this.queryEngine = queryEngine;
    }

    public void start() {
        networkModule.start();
    }

    public void stop() {
        networkModule.stop();
    }
}
