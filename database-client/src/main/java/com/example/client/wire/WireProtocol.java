package com.example.client.wire;

/**
 * Must match {@code com.example.database.network.wire.WireProtocol} on the server.
 * Kept as a duplicate type so {@code database-client} stays a standalone app with no
 * server JAR dependency; the contract lives in {@code docs/protocol/wire-protocol.md}.
 */
public final class WireProtocol {

    public static final int VERSION = 1;

    private WireProtocol() {
    }
}
