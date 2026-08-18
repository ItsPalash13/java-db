package com.example.database.network.tcp;

import com.example.database.network.requesthandler.DefaultRequestHandler;
import com.example.database.network.requesthandler.RequestHandler;
import com.example.database.network.ClientConnection;
import com.example.database.network.NetworkModule;
import com.example.database.network.Request;
import com.example.database.network.Response;
import com.example.database.network.ServerSocket;
import com.example.database.processor.QueryProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP {@link NetworkModule}: one accept thread plus a cached pool of per-connection workers.
 * Owns {@link RequestHandler}; receives {@link QueryProcessor} from the composition root and wires it in.
 * <p>
 * Daemon threads so process lifetime stays with {@code Main} / explicit {@link #stop()}.
 */
public final class TcpNetworkModule implements NetworkModule {

    private final ServerSocket serverSocket;
    private final RequestHandler requestHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool(namedFactory("db-conn"));

    private Thread acceptThread;

    /**
     * @param serverSocket   already-bound listen socket (created outside so port/bind stay at the edge)
     * @param queryProcessor shared processor used by {@code DatabaseServer} (no lifecycle)
     */
    public TcpNetworkModule(ServerSocket serverSocket, QueryProcessor queryProcessor) {
        this.serverSocket = serverSocket;
        this.requestHandler = new DefaultRequestHandler(queryProcessor);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "db-accept");
        // Daemon: must not keep the JVM alive by itself after Main exits.
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("Network module listening on port " + serverSocket.getPort());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Closing the listen socket unblocks accept() in the kernel; interrupt alone is unreliable.
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

    /** Accept loop only; per-connection I/O and query work run on pool threads. */
    private void acceptLoop() {
        while (running.get()) {
            try {
                ClientConnection connection = serverSocket.accept();
                workers.submit(() -> handle(connection));
            } catch (IOException e) {
                if (running.get()) {
                    throw new UncheckedIOException("Failed to accept connection", e);
                }
                // IOException while !running is expected after close() during stop().
            }
        }
    }

    /**
     * One worker owns one connection for its lifetime: receive → handler → send, repeatedly.
     * Handler and query processor run on this same thread (synchronous pipeline).
     */
    private void handle(ClientConnection connection) {
        try {
            while (running.get()) {
                Request request = connection.receive();
                System.out.println("[NetworkModule] received request, dispatching to RequestHandler");
                Response response = requestHandler.handle(request);
                connection.send(response);
            }
        } catch (IOException ignored) {
            // Peer closed or socket closed during stop().
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
            // Best-effort cleanup during shutdown races.
        }
    }
}
