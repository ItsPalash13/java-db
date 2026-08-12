package com.example.database.network.tcp;

import com.example.database.network.ClientConnection;
import com.example.database.network.NetworkModule;
import com.example.database.network.Request;
import com.example.database.network.ServerSocket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TcpNetworkModule implements NetworkModule {

    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool(namedFactory("db-conn"));

    private Thread acceptThread;

    public TcpNetworkModule(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "db-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("Network module listening on port " + serverSocket.getPort());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(serverSocket);
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                ClientConnection connection = serverSocket.accept();
                workers.submit(() -> handle(connection));
            } catch (IOException e) {
                if (running.get()) {
                    throw new UncheckedIOException("Failed to accept connection", e);
                }
            }
        }
    }

    private void handle(ClientConnection connection) {
        try {
            while (running.get()) {
                Request request = connection.receive();
                connection.send(new TcpResponse("OK " + request.decode()));
            }
        } catch (IOException ignored) {
            // peer closed or server is stopping
        } finally {
            closeQuietly(connection);
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // already closed
        }
    }
}
