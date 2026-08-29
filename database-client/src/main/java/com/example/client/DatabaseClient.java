package com.example.client;

import com.example.client.wire.WireResponse;
import com.example.client.wire.WireResponseJson;

import java.io.IOException;

/**
 * Client API over one persistent TCP connection. Each {@link #executeQuery} is one
 * length-prefixed request frame and one JSON {@link WireResponse} frame — same
 * synchronous contract as production DB clients, without pipelining.
 */
public final class DatabaseClient implements AutoCloseable {

    private final ClientConnection connection;

    public DatabaseClient(String host, int port) throws IOException {
        this.connection = new ClientConnection(host, port);
    }

    /**
     * Send SQL text and block until the server returns a full wire response.
     * Caller must not invoke again until this returns — frames are strictly ordered.
     */
    public WireResponse executeQuery(String sql) throws IOException {
        connection.send(sql);
        return WireResponseJson.parse(connection.receive());
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
