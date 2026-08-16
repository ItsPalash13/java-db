package com.example.database.engine;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryEngineTest {

    @TempDir
    Path dataDir;

    @Test
    void executeReturnsOkForValidQuery() {
        DefaultQueryEngine engine = startedEngine();
        try {
            assertEquals("OK SELECT * FROM users", engine.execute("SELECT * FROM users"));
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsLexErrorWithExactIndex() {
        DefaultQueryEngine engine = startedEngine();
        try {
            String response = engine.execute("SELECT @ bad");
            assertEquals("ERROR at index 7: unexpected character '@'", response);
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsLexErrorForUnclosedString() {
        DefaultQueryEngine engine = startedEngine();
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
        DefaultQueryEngine engine = startedEngine();
        try {
            String response = engine.execute("CREATE TABLE users");
            assertTrue(response.contains("expected LPAREN"));
            assertTrue(response.startsWith("ERROR at index "));
        } finally {
            engine.stop();
        }
    }

    @Test
    void executeReturnsOkForCreateAlterDropInsertUpdateDelete() {
        DefaultQueryEngine engine = startedEngine();
        try {
            assertEquals("OK CREATE DATABASE mydb", engine.execute("CREATE DATABASE mydb"));
            assertEquals(
                    "OK CREATE TABLE users (id, name)",
                    engine.execute("CREATE TABLE users (id, name)")
            );
            assertEquals(
                    "OK CREATE INDEX idx ON users (id)",
                    engine.execute("CREATE INDEX idx ON users (id)")
            );
            assertEquals(
                    "OK ALTER TABLE users ADD age",
                    engine.execute("ALTER TABLE users ADD age")
            );
            assertEquals("OK DROP INDEX idx", engine.execute("DROP INDEX idx"));
            assertEquals("OK DROP TABLE users", engine.execute("DROP TABLE users"));
            assertEquals("OK INSERT INTO t VALUES (1)", engine.execute("INSERT INTO t VALUES (1)"));
            assertEquals("OK UPDATE t SET a = 1", engine.execute("UPDATE t SET a = 1"));
            assertEquals("OK DELETE FROM t", engine.execute("DELETE FROM t"));
        } finally {
            engine.stop();
        }
    }

    private DefaultQueryEngine startedEngine() {
        StorageEngine storage = new DefaultStorageEngine(new DataDirectory(dataDir));
        storage.start();
        DefaultQueryEngine engine = new DefaultQueryEngine(storage);
        engine.start();
        return engine;
    }
}
