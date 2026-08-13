package com.example.database;

import com.example.database.engine.DefaultQueryEngine;
import com.example.database.engine.QueryEngine;
import com.example.database.network.NetworkModule;
import com.example.database.network.ServerSocket;
import com.example.database.network.tcp.TcpNetworkModule;
import com.example.database.network.tcp.TcpServerSocket;
import com.example.database.server.DatabaseServer;

import java.io.IOException;

public final class Main {

    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        QueryEngine queryEngine = new DefaultQueryEngine();
        ServerSocket serverSocket = new TcpServerSocket(port);
        NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryEngine);
        DatabaseServer server = new DatabaseServer(networkModule, queryEngine);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "db-shutdown"));
        server.start();
        Thread.currentThread().join();
    }
}
