package com.example.database.network.tcp;

/**
 * Text payload response over TCP.
 */
public final class TextResponse extends TcpResponse {

    public TextResponse(String payload) {
        super(payload);
    }
}
