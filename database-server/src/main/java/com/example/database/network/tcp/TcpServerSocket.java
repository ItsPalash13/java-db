package com.example.database.network.tcp;

import com.example.database.network.ClientConnection;
import com.example.database.network.ServerSocket;

import java.io.IOException;

public final class TcpServerSocket implements ServerSocket {

    private final java.net.ServerSocket socket;
    private final int port;

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
        return port == 0 ? socket.getLocalPort() : port;
    }
}
