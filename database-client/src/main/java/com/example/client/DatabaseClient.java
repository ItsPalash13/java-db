package com.example.client;

import java.io.IOException;

public final class DatabaseClient implements AutoCloseable {

    private final ClientConnection connection;

    public DatabaseClient(String host, int port) throws IOException {
        this.connection = new ClientConnection(host, port);
    }

    public String execute(String request) throws IOException {
        connection.send(request);
        return connection.receive();
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
