package com.example.client.wire;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireResponseJsonTest {

    @Test
    void parsesOkResponse() {
        WireResponse response = WireResponseJson.parse(
                "{\"v\":1,\"messages\":[{\"type\":\"OK\",\"rowsAffected\":0}]}"
        );
        assertEquals(WireProtocol.VERSION, response.version());
        assertEquals(new WireMessage.Ok(0), response.messages().get(0));
    }

    @Test
    void parsesResultSetAndDone() {
        String json = "{\"v\":1,\"messages\":["
                + "{\"type\":\"RESULT_SET\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}],\"rows\":[[1,\"a\"]]},"
                + "{\"type\":\"DONE\",\"rowsAffected\":1}"
                + "]}";
        WireResponse response = WireResponseJson.parse(json);
        assertEquals(2, response.messages().size());
        WireMessage.ResultSet resultSet = (WireMessage.ResultSet) response.messages().get(0);
        assertEquals("id", resultSet.columns().get(0).name());
        assertEquals(List.of(1L, "a"), resultSet.rows().get(0));
        assertEquals(new WireMessage.Done(1), response.messages().get(1));
    }

    @Test
    void parsesResultSetWithSqlNullCell() {
        String json = "{\"v\":1,\"messages\":["
                + "{\"type\":\"RESULT_SET\",\"columns\":["
                + "{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"active\",\"type\":\"BOOLEAN\"}"
                + "],\"rows\":[[3,null]]},"
                + "{\"type\":\"DONE\",\"rowsAffected\":1}"
                + "]}";
        WireResponse response = WireResponseJson.parse(json);
        WireMessage.ResultSet resultSet = (WireMessage.ResultSet) response.messages().get(0);
        assertEquals(3L, resultSet.rows().get(0).get(0));
        assertEquals(null, resultSet.rows().get(0).get(1));
    }
}
