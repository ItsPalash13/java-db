package com.example.database.server;

import com.example.database.network.NetworkModule;

public final class DatabaseServer {

    private final NetworkModule networkModule;

    public DatabaseServer(NetworkModule networkModule) {
        this.networkModule = networkModule;
    }

    public void start() {
        networkModule.start();
    }

    public void stop() {
        networkModule.stop();
    }
}
