package com.example.database.network.tcp;

import com.example.database.network.ClientConnection;
import com.example.database.network.ServerSocket;

import java.io.IOException;

/**
 * TCP implementation of {@link ServerSocket}.
 * {@code java.net.ServerSocket} stays here so upper layers never see it.
 */
public final class TcpServerSocket implements ServerSocket {

    private final java.net.ServerSocket socket;
    private final int port;

    /**
     * Binds immediately. Pass {@code 0} to let the OS pick an ephemeral port (useful in tests).
     */
    public TcpServerSocket(int port) throws IOException {
        this.port = port;
        this.socket = new java.net.ServerSocket(port);
    }

    @Override
    public ClientConnection accept() throws IOException {
        return new TcpClientConnection(socket.accept());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    @Override
    public int getPort() {
        // When bound with 0, report the real local port from the OS.
        return port == 0 ? socket.getLocalPort() : port;
    }
}
