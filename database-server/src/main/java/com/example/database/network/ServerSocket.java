package com.example.database.network;

import java.io.IOException;

/**
 * Abstraction over a listening socket.
 * Hides {@code java.net.ServerSocket} so the rest of the server can stay transport-agnostic.
 */
public interface ServerSocket extends AutoCloseable {

    /**
     * Blocks until a client connects, then returns a connection handle.
     * Closing this listen socket unblocks a waiting {@code accept()} with an {@link IOException}.
     */
    ClientConnection accept() throws IOException;

    @Override
    void close() throws IOException;

    /** Bound local port; if constructed with {@code 0}, returns the ephemeral port chosen by the OS. */
    int getPort();
}
