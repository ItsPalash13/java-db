package com.example.database.network.tcp;

import com.example.database.network.Response;
import com.example.database.network.wire.WireResponse;
import com.example.database.network.wire.WireResponseJson;

/**
 * TCP {@link Response} whose frame payload is JSON {@link WireResponse}.
 * <p>
 * Lives in the network layer so {@link com.example.database.processor.QueryProcessor} stays
 * text-oriented; handlers map processor strings here via {@code WireResponseEncoder}.
 */
public final class JsonWireResponse implements Response {

    private final WireResponse wireResponse;

    public JsonWireResponse(WireResponse wireResponse) {
        this.wireResponse = wireResponse;
    }

    @Override
    public byte[] encode() {
        return WireResponseJson.toBytes(wireResponse);
    }
}
