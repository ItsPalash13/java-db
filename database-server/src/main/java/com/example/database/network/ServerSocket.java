package com.example.database.network;

import java.io.IOException;

public interface ServerSocket extends AutoCloseable {

    ClientConnection accept() throws IOException;

    @Override
    void close() throws IOException;

    int getPort();
}
