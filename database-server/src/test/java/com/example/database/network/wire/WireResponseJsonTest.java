package com.example.database.network.wire;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WireResponseJsonTest {

    @Test
    void encodesOkMessage() {
        WireResponse response = new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Ok(0)));
        assertEquals("{\"v\":1,\"messages\":[{\"type\":\"OK\",\"rowsAffected\":0}]}", WireResponseJson.toJson(response));
    }

    @Test
    void encodesErrorMessageWithEscapes() {
        WireResponse response = new WireResponse(
                WireProtocol.VERSION,
                List.of(new WireMessage.Error("ERROR at index 0: bad \"quote\""))
        );
        assertEquals(
                "{\"v\":1,\"messages\":[{\"type\":\"ERROR\",\"message\":\"ERROR at index 0: bad \\\"quote\\\"\"}]}",
                WireResponseJson.toJson(response)
        );
    }

    @Test
    void encodesResultSetWithNullAndNumbers() {
        WireResponse response = new WireResponse(
                WireProtocol.VERSION,
                List.of(
                        new WireMessage.ResultSet(
                                List.of(new WireMessage.ResultSet.Column("id", "INT")),
                                List.of(Arrays.asList(1, null))
                        ),
                        new WireMessage.Done(1)
                )
        );
        assertEquals(
                "{\"v\":1,\"messages\":[{\"type\":\"RESULT_SET\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}],"
                        + "\"rows\":[[1,null]]},{\"type\":\"DONE\",\"rowsAffected\":1}]}",
                WireResponseJson.toJson(response)
        );
    }
}
