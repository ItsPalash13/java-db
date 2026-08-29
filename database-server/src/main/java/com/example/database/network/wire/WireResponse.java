package com.example.database.network.wire;

import java.util.List;

/** One TCP response frame: versioned list of {@link WireMessage} items processed in order. */
public record WireResponse(int version, List<WireMessage> messages) {

    public WireResponse {
        if (version != WireProtocol.VERSION) {
            throw new IllegalArgumentException("unsupported wire version: " + version);
        }
        messages = List.copyOf(messages);
    }
}
