package com.example.database.network;

import java.io.IOException;

public interface ClientConnection extends AutoCloseable {

    ConnectionId getId();

    Request receive() throws IOException;

    void send(Response response) throws IOException;

    @Override
    void close() throws IOException;
}
