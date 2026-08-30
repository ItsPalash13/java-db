package com.example.database.network.tcp;

import com.example.database.network.NetworkModule;
import com.example.database.processor.DefaultQueryProcessor;
import com.example.database.processor.QueryProcessor;
import com.example.database.server.DatabaseServer;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionDisconnectTransactionTest {

    @TempDir
    Path dataDir;

    @Test
    void disconnectRollsBackExplicitTransactionAndReleasesCatalogLock() throws Exception {
        StorageEngine storageEngine = new DefaultStorageEngine(new DataDirectory(dataDir));
        QueryProcessor queryProcessor = new DefaultQueryProcessor(storageEngine);
        TcpServerSocket serverSocket = new TcpServerSocket(0);
        int port = serverSocket.getPort();
        NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryProcessor);
        DatabaseServer server = new DatabaseServer(storageEngine, networkModule, queryProcessor);
        server.start();

        try {
            Socket firstClient = new Socket("127.0.0.1", port);
            sendQuery(firstClient, "BEGIN");
            assertTrue(readResponse(firstClient).contains("\"OK\""));
            firstClient.close();

            try (Socket secondClient = new Socket("127.0.0.1", port)) {
                sendQuery(secondClient, "BEGIN");
                assertTrue(readResponse(secondClient).contains("\"OK\""));
            }
        } finally {
            server.stop();
        }
    }

    private static void sendQuery(Socket socket, String sql) throws Exception {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        byte[] request = sql.getBytes(StandardCharsets.UTF_8);
        out.write(ByteBuffer.allocate(4).putInt(request.length).array());
        out.write(request);
        out.flush();
    }

    private static String readResponse(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        byte[] lengthBytes = in.readNBytes(4);
        int length = ByteBuffer.wrap(lengthBytes).getInt();
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
