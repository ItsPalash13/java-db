package com.example.database.network.tcp;

import com.example.database.engine.DefaultQueryEngine;
import com.example.database.engine.QueryEngine;
import com.example.database.network.NetworkModule;
import com.example.database.server.DatabaseServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TcpNetworkModuleTest {

    @Test
    void serverAcceptsRequestAndReturnsEncodedResponse() throws Exception {
        QueryEngine queryEngine = new DefaultQueryEngine();
        TcpServerSocket serverSocket = new TcpServerSocket(0);
        int port = serverSocket.getPort();
        NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryEngine);
        DatabaseServer server = new DatabaseServer(networkModule, queryEngine);
        server.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());

            byte[] request = "SELECT 1".getBytes(StandardCharsets.UTF_8);
            out.write(ByteBuffer.allocate(4).putInt(request.length).array());
            out.write(request);
            out.flush();

            byte[] lengthBytes = in.readNBytes(4);
            int length = ByteBuffer.wrap(lengthBytes).getInt();
            String response = new String(in.readNBytes(length), StandardCharsets.UTF_8);

            assertEquals("OK SELECT 1", response);
        } finally {
            server.stop();
        }
    }
}
