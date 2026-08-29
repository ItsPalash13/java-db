package com.example.database.network.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireResponseEncoderTest {

    @Test
    void mapsOkToWireMessage() {
        WireResponse response = WireResponseEncoder.fromProcessorText("OK");
        assertEquals(1, response.messages().size());
        assertEquals(new WireMessage.Ok(0), response.messages().get(0));
    }

    @Test
    void mapsErrorPrefixToErrorMessage() {
        WireResponse response = WireResponseEncoder.fromProcessorText("ERROR: table already exists: shop.users");
        assertEquals(new WireMessage.Error("ERROR: table already exists: shop.users"), response.messages().get(0));
    }

    @Test
    void mapsUnresolvedEchoToOk() {
        WireResponse response = WireResponseEncoder.fromProcessorText("OK SELECT * FROM shop.t");
        assertEquals(new WireMessage.Ok(0), response.messages().get(0));
    }
}
