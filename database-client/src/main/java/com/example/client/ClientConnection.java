package com.example.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Client-side TCP session using the same length-prefixed framing as the server
 * ({@code [4-byte big-endian length][UTF-8 payload]}, max 1 MiB).
 * Request payloads are plain SQL; response payloads are JSON wire messages (see
 * {@code docs/protocol/wire-protocol.md}) — framing stays here, not in the codec.
 */
public final class ClientConnection implements AutoCloseable {

    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public ClientConnection(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public void send(String request) throws IOException {
        writeFrame(request.getBytes(StandardCharsets.UTF_8));
    }

    public String receive() throws IOException {
        byte[] payload = readFrame();
        if (payload == null) {
            throw new EOFException("Server closed the connection");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** @return payload, or {@code null} on clean EOF before a frame */
    private byte[] readFrame() throws IOException {
        byte[] lengthBytes = in.readNBytes(4);
        if (lengthBytes.length == 0) {
            return null;
        }
        if (lengthBytes.length < 4) {
            throw new EOFException("Incomplete frame length");
        }
        int length = ByteBuffer.wrap(lengthBytes).getInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new EOFException("Incomplete frame payload");
        }
        return payload;
    }

    private void writeFrame(byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Payload exceeds max frame size");
        }
        out.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }
}
