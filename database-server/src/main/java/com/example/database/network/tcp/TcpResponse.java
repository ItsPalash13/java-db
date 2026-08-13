package com.example.database.network.tcp;

import com.example.database.network.Response;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * TCP response with a UTF-8 payload.
 */
public class TcpResponse implements Response {

    private final String payload;

    public TcpResponse(String payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    protected final String payload() {
        return payload;
    }

    @Override
    public byte[] encode() {
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
