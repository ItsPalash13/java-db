package com.example.database.network.tcp;

import com.example.database.network.ClientConnection;
import com.example.database.network.ConnectionId;
import com.example.database.network.Request;
import com.example.database.network.Response;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public final class TcpClientConnection implements ClientConnection {

    private final Socket socket;
    private final ConnectionId id;
    private final InputStream in;
    private final OutputStream out;

    public TcpClientConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.id = ConnectionId.random();
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    @Override
    public ConnectionId getId() {
        return id;
    }

    @Override
    public Request receive() throws IOException {
        byte[] payload = LengthPrefixedIo.readFrame(in);
        if (payload == null) {
            throw new EOFException("Connection closed by peer: " + id);
        }
        return new TcpRequest(payload);
    }

    @Override
    public void send(Response response) throws IOException {
        LengthPrefixedIo.writeFrame(out, response.encode());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
