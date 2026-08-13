package com.example.database.network.tcp;

import com.example.database.network.Request;

import java.nio.charset.StandardCharsets;

/** TCP inbound frame decoded as UTF-8 text. */
final class TcpRequest implements Request {

    private final byte[] payload;

    TcpRequest(byte[] payload) {
        this.payload = payload;
    }

    @Override
    public String decode() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
