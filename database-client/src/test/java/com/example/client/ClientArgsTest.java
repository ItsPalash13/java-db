package com.example.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientArgsTest {

    @Test
    void defaultsToReplLocalhost() {
        ClientArgs args = ClientArgs.parse(new String[]{});
        assertFalse(args.scriptMode());
        assertEquals("127.0.0.1", args.host());
        assertEquals(9090, args.port());
    }

    @Test
    void parsesHostPort() {
        ClientArgs args = ClientArgs.parse(new String[]{"10.0.0.1", "9191"});
        assertEquals("10.0.0.1", args.host());
        assertEquals(9191, args.port());
    }

    @Test
    void parsesScriptMode() {
        ClientArgs args = ClientArgs.parse(new String[]{
                "--script", "in.txt", "--out", "out.txt", "--stop-on-error", "127.0.0.1", "9091"
        });
        assertTrue(args.scriptMode());
        assertTrue(args.stopOnError());
        assertEquals(Path.of("in.txt"), args.scriptPath().orElseThrow());
        assertEquals(Path.of("out.txt"), args.outPath().orElseThrow());
        assertEquals("127.0.0.1", args.host());
        assertEquals(9091, args.port());
    }

    @Test
    void scriptRequiresOut() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientArgs.parse(new String[]{"--script", "in.txt"}));
    }
}
