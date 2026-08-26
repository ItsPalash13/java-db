package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryProcessorTest {

    @TempDir
    Path dataDir;

    @Test
    void executeReturnsOkForValidQuery() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK SELECT * FROM shop.users", processor.execute("SELECT * FROM shop.users"));
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
        assertTrue(response.contains("expected DOT"));
        assertTrue(response.startsWith("ERROR at index "));
    }

    @Test
    void executeCreateTableWritesCatalogAndRejectsDuplicates() {
        DefaultQueryProcessor processor = newProcessor();

        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertEquals("OK", processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals("shop", users.database());
        assertEquals("users", users.name());
        assertEquals(2, users.columns().size());
        assertEquals("id", users.columns().get(0).name());
        assertEquals(ColumnType.INT, users.columns().get(0).type());
        assertEquals("name", users.columns().get(1).name());
        assertEquals(ColumnType.VARCHAR, users.columns().get(1).type());
        assertTrue(Files.isRegularFile(dataDir.resolve("shop").resolve("users").resolve("catalog.json")));

        assertEquals(
                "ERROR: table already exists: shop.users",
                processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeCreateTableWithoutDatabaseIsAnalysisError() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeDropTableRemovesCatalogThenAllowsDropDatabase() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertEquals("OK", processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals(
                "ERROR: database is not empty: shop",
                processor.execute("DROP DATABASE shop")
        );

        assertEquals("OK", processor.execute("DROP TABLE shop.users"));
        assertFalse(processor.storageEngine().catalogManager().tableExists("shop", "users"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop").resolve("users")));

        assertEquals(
                "ERROR: table does not exist: shop.users",
                processor.execute("DROP TABLE shop.users")
        );

        assertEquals("OK", processor.execute("DROP DATABASE shop"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop")));
    }

    @Test
    void executeCreateAndDropDatabase() {
        DefaultQueryProcessor processor = newProcessor();

        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertTrue(processor.storageEngine().catalogManager().databaseExists("shop"));
        assertTrue(Files.isDirectory(dataDir.resolve("shop")));

        assertEquals(
                "ERROR: database already exists: shop",
                processor.execute("CREATE DATABASE shop")
        );

        assertEquals("OK", processor.execute("DROP DATABASE shop"));
        assertFalse(processor.storageEngine().catalogManager().databaseExists("shop"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop")));

        assertEquals(
                "ERROR: database does not exist: shop",
                processor.execute("DROP DATABASE shop")
        );
    }

    @Test
    void executeReturnsOkForUnresolvedCreateAlterDropInsertUpdateDelete() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "OK CREATE INDEX idx ON shop.users (id)",
                processor.execute("CREATE INDEX idx ON shop.users (id)")
        );
        assertEquals(
                "OK ALTER TABLE shop.users ADD age",
                processor.execute("ALTER TABLE shop.users ADD age")
        );
        assertEquals("OK DROP INDEX idx", processor.execute("DROP INDEX idx"));
        assertEquals("OK INSERT INTO shop.t VALUES (1)", processor.execute("INSERT INTO shop.t VALUES (1)"));
        assertEquals("OK UPDATE shop.t SET a = 1", processor.execute("UPDATE shop.t SET a = 1"));
        assertEquals("OK DELETE FROM shop.t", processor.execute("DELETE FROM shop.t"));
    }

    @Test
    void executeReturnsAnalysisErrorWhenCreateTableTargetAlreadyExists() {
        DefaultQueryProcessor processor = newProcessor();
        processor.storageEngine().catalogManager().createDatabase("shop");
        processor.storageEngine().catalogManager().createTable(TableMetadata.define(
                "shop",
                "users",
                java.util.List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals(
                "ERROR: table already exists: shop.users",
                processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    private DefaultQueryProcessor newProcessor() {
        StorageEngine storage = new DefaultStorageEngine(new DataDirectory(dataDir));
        storage.start();
        return new DefaultQueryProcessor(storage);
    }
}
