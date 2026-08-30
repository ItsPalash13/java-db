package com.example.database;

import com.example.database.config.ServerEnvironment;
import com.example.database.network.NetworkModule;
import com.example.database.network.ServerSocket;
import com.example.database.network.tcp.TcpNetworkModule;
import com.example.database.network.tcp.TcpServerSocket;
import com.example.database.processor.DefaultQueryProcessor;
import com.example.database.processor.QueryProcessor;
import com.example.database.server.DatabaseServer;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;

import java.io.IOException;

/**
 * Composition root: wires concrete TCP + processor implementations into {@link DatabaseServer}.
 * <p>
 * Ownership: server owns storage and network lifecycles; uses {@link QueryProcessor}.
 * Network creates/owns the request handler with the same processor instance.
 * Port and data-dir stay at this edge (same rule as listen bind).
 */
public final class Main {

    public static void main(String[] args) throws IOException, InterruptedException {
        LaunchConfig config = LaunchConfig.parse(args);
        DataDirectory dataDirectory = new DataDirectory(config.dataDir());
        dataDirectory.ensureExists();
        ServerEnvironment environment = ServerEnvironment.load(dataDirectory);
        System.out.println(
                "[Main] catalog lock wait: " + environment.catalogLockWait().toSeconds() + "s"
        );
        StorageEngine storageEngine = new DefaultStorageEngine(dataDirectory, environment);

        QueryProcessor queryProcessor = new DefaultQueryProcessor(storageEngine);
        // Listen socket is created here so bind/port stay at the edge (not inside TcpNetworkModule).
        ServerSocket serverSocket = new TcpServerSocket(config.port());
        NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryProcessor);
        DatabaseServer server = new DatabaseServer(storageEngine, networkModule, queryProcessor);

        // Register before start so Ctrl+C / SIGTERM still tears down if start fails partway.
        // Hook runs on JVM shutdown (not kill -9); closes network then storage via server.stop().
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "db-shutdown"));
        server.start();
        // Keep the non-daemon main thread alive; accept/worker threads are daemon.
        Thread.currentThread().join();
    }
}
