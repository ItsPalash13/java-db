package com.example.database.network.tcp;

/**
 * Text query-result response over TCP.
 * Extends {@link TcpResponse} so handlers can return a typed text result without reimplementing encode.
 */
public final class TextResponse extends TcpResponse {

    public TextResponse(String payload) {
        super(payload);
    }
}
