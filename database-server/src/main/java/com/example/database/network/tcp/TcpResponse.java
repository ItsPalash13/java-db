package com.example.database.network.tcp;

import com.example.database.network.Response;

import java.nio.charset.StandardCharsets;

final class TcpResponse implements Response {

    private final String payload;

    TcpResponse(String payload) {
        this.payload = payload;
    }

    @Override
    public byte[] encode() {
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
