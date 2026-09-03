package com.example.client.wire;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsePrinterTest {

    @Test
    void printsOkAndTable() {
        WireResponse ok = new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Ok(0)));
        WireResponse select = new WireResponse(
                WireProtocol.VERSION,
                List.of(
                        new WireMessage.ResultSet(
                                List.of(
                                        new WireMessage.ResultSet.Column("id", "INT"),
                                        new WireMessage.ResultSet.Column("name", "VARCHAR")
                                ),
                                List.of(List.of(1L, "alice"))
                        ),
                        new WireMessage.Done(1)
                )
        );

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ResponsePrinter printer = new ResponsePrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.print(ok);
        printer.print(select);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("OK"));
        assertTrue(output.contains("id"));
        assertTrue(output.contains("alice"));
        assertTrue(output.contains("(1 rows)"));
        assertEquals(1, output.split("id \\| name", -1).length - 1);
        assertEquals(1, output.split("\\(1 rows\\)", -1).length - 1);
    }

    @Test
    void printsSqlNullAsNULL() {
        WireResponse select = new WireResponse(
                WireProtocol.VERSION,
                List.of(
                        new WireMessage.ResultSet(
                                List.of(
                                        new WireMessage.ResultSet.Column("id", "INT"),
                                        new WireMessage.ResultSet.Column("active", "BOOLEAN")
                                ),
                                List.of(java.util.Arrays.asList(3L, null))
                        ),
                        new WireMessage.Done(1)
                )
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ResponsePrinter printer = new ResponsePrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.print(select);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("NULL"));
    }
}
