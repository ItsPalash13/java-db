package com.example.database.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

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

    @Test
    void executeReturnsParseErrorWithExactIndex() {
        DefaultQueryEngine engine = new DefaultQueryEngine();
        engine.start();
        try {
            String response = engine.execute("CREATE TABLE users");
            assertTrue(response.contains("expected LPAREN"));
            assertTrue(response.startsWith("ERROR at index "));
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsOkForCreateInsertUpdateDelete() {
        DefaultQueryEngine engine = new DefaultQueryEngine();
        engine.start();
        try {
            assertEquals("OK CREATE DATABASE mydb", engine.execute("CREATE DATABASE mydb"));
            assertEquals(
                    "OK CREATE TABLE users (id, name)",
                    engine.execute("CREATE TABLE users (id, name)")
            );
            assertEquals("OK INSERT INTO t VALUES (1)", engine.execute("INSERT INTO t VALUES (1)"));
            assertEquals("OK UPDATE t SET a = 1", engine.execute("UPDATE t SET a = 1"));
            assertEquals("OK DELETE FROM t", engine.execute("DELETE FROM t"));
        } finally {
            engine.stop();
        }
    }
}
