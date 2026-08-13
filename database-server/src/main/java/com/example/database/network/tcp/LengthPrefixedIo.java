package com.example.database.network.tcp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Shared framing for client and server TCP paths:
 * {@code [4-byte big-endian length][payload bytes]}, max 1 MiB payload.
 */
final class LengthPrefixedIo {

    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private LengthPrefixedIo() {
    }

    /**
     * @return payload bytes, or {@code null} if the stream is cleanly at EOF before a frame
     */
    static byte[] readFrame(InputStream in) throws IOException {
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

    static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Payload exceeds max frame size");
        }
        out.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }
}
