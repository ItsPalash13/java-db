package com.example.database.network.wire;

/**
 * Wire protocol version shared with {@code database-client}.
 * <p>
 * JSON payload lives inside the existing length-prefixed TCP frame — we only changed
 * the bytes inside the frame, not transport or threading. Real DBs use binary tokens;
 * JSON keeps debugging easy until SELECT result sets need a binary codec.
 */
public final class WireProtocol {

    /** Bump when message shapes change; client rejects unknown versions. */
    public static final int VERSION = 1;

    private WireProtocol() {
    }
}
