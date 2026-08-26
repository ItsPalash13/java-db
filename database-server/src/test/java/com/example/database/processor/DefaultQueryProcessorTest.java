package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryProcessorTest {

    @TempDir
    Path dataDir;

    @Test
    void executeReturnsOkForValidQuery() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK SELECT * FROM users", processor.execute("SELECT * FROM users"));
    }

    @Test
    void executeReturnsLexErrorWithExactIndex() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.execute("SELECT @ bad");
        assertEquals("ERROR at index 7: unexpected character '@'", response);
    }

    @Test
    void executeReturnsLexErrorForUnclosedString() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.execute("INSERT INTO t VALUES ('x");
        assertTrue(response.startsWith("ERROR at index "));
        assertTrue(response.contains("unclosed string literal"));
    }

    @Test
    void executeReturnsParseErrorWithExactIndex() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.execute("CREATE TABLE users");
        assertTrue(response.contains("expected LPAREN"));
        assertTrue(response.startsWith("ERROR at index "));
    }

    @Test
    void executeCreateTableWritesCatalogAndRejectsDuplicates() {
        DefaultQueryProcessor processor = newProcessor();

        assertEquals("OK", processor.execute("CREATE TABLE users (id INT, name VARCHAR)"));

        TableMetadata users = processor.storageEngine().catalogManager().getTable("users").orElseThrow();
        assertEquals("users", users.name());
        assertEquals(2, users.columns().size());
        assertEquals("id", users.columns().get(0).name());
        assertEquals(ColumnType.INT, users.columns().get(0).type());
        assertEquals("name", users.columns().get(1).name());
        assertEquals(ColumnType.VARCHAR, users.columns().get(1).type());

        assertEquals(
                "ERROR: table already exists: users",
                processor.execute("CREATE TABLE users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeReturnsOkForUnresolvedCreateAlterDropInsertUpdateDelete() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK CREATE DATABASE mydb", processor.execute("CREATE DATABASE mydb"));
        assertEquals(
                "OK CREATE INDEX idx ON users (id)",
                processor.execute("CREATE INDEX idx ON users (id)")
        );
        assertEquals(
                "OK ALTER TABLE users ADD age",
                processor.execute("ALTER TABLE users ADD age")
        );
        assertEquals("OK DROP INDEX idx", processor.execute("DROP INDEX idx"));
        assertEquals("OK DROP TABLE users", processor.execute("DROP TABLE users"));
        assertEquals("OK INSERT INTO t VALUES (1)", processor.execute("INSERT INTO t VALUES (1)"));
        assertEquals("OK UPDATE t SET a = 1", processor.execute("UPDATE t SET a = 1"));
        assertEquals("OK DELETE FROM t", processor.execute("DELETE FROM t"));
    }

    @Test
    void executeReturnsAnalysisErrorWhenCreateTableTargetAlreadyExists() {
        DefaultQueryProcessor processor = newProcessor();
        processor.storageEngine().catalogManager().createTable(TableMetadata.define(
                "users",
                java.util.List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals(
                "ERROR: table already exists: users",
                processor.execute("CREATE TABLE users (id INT, name VARCHAR)")
        );
    }

    private DefaultQueryProcessor newProcessor() {
        StorageEngine storage = new DefaultStorageEngine(new DataDirectory(dataDir));
        storage.start();
        return new DefaultQueryProcessor(storage);
    }
}
