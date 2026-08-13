package com.example.database.network;

import java.io.IOException;

/**
 * One accepted client session. Implementations own framing; callers work with {@link Request} / {@link Response}.
 */
public interface ClientConnection extends AutoCloseable {

    ConnectionId getId();

    /** Blocks until a full framed request arrives, or throws when the peer closes. */
    Request receive() throws IOException;

    void send(Response response) throws IOException;

    @Override
    void close() throws IOException;
}
