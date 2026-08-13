package com.example.database.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryEngineTest {

    @Test
    void executeReturnsOkForValidQuery() {
        DefaultQueryEngine engine = new DefaultQueryEngine();
        engine.start();
        try {
            assertEquals("OK SELECT * FROM users", engine.execute("SELECT * FROM users"));
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsLexErrorWithExactIndex() {
        DefaultQueryEngine engine = new DefaultQueryEngine();
        engine.start();
        try {
            String response = engine.execute("SELECT @ bad");
            assertEquals("ERROR at index 7: unexpected character '@'", response);
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsLexErrorForUnclosedString() {
        DefaultQueryEngine engine = new DefaultQueryEngine();
        engine.start();
        try {
            String response = engine.execute("INSERT INTO t VALUES ('x");
            assertTrue(response.startsWith("ERROR at index "));
            assertTrue(response.contains("unclosed string literal"));
        } finally {
            engine.stop();
        }
    }
}
