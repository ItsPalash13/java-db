package com.example.client.wire;

import java.util.List;

/** Decoded JSON payload from one server response frame. */
public record WireResponse(int version, List<WireMessage> messages) {

    public WireResponse {
        if (version != WireProtocol.VERSION) {
            throw new WireParseException("unsupported wire version: " + version);
        }
        messages = List.copyOf(messages);
    }
}
