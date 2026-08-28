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
import java.util.List;

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
    void executeAddColumnPersistsAndRejectsDuplicate() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertEquals("OK", processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.execute("ALTER TABLE shop.users ADD age INT"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(3, users.columns().size());
        assertEquals("age", users.columns().get(2).name());
        assertEquals(ColumnType.INT, users.columns().get(2).type());
        assertEquals(3, users.columns().get(2).columnId().orElseThrow());

        assertEquals(
                "ERROR: duplicate column name: age",
                processor.execute("ALTER TABLE shop.users ADD age INT")
        );
    }

    @Test
    void executeDropColumnPersistsAndRejectsMissing() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertEquals("OK", processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.execute("ALTER TABLE shop.users DROP COLUMN name"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(1, users.columns().size());
        assertEquals("id", users.columns().get(0).name());

        assertEquals(
                "ERROR: column does not exist: name",
                processor.execute("ALTER TABLE shop.users DROP COLUMN name")
        );
    }

    @Test
    void executeCreateAndDropIndexPersistDefinitions() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.execute("CREATE DATABASE shop"));
        assertEquals("OK", processor.execute("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.execute("CREATE INDEX idx_users_id ON shop.users (id)"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(1, users.indexes().size());
        assertEquals("idx_users_id", users.indexes().get(0).name());
        assertEquals(List.of(1), users.indexes().get(0).columnIds());
        assertFalse(users.indexes().get(0).unique());

        assertEquals(
                "ERROR: index already exists: idx_users_id",
                processor.execute("CREATE INDEX idx_users_id ON shop.users (name)")
        );

        assertEquals("OK", processor.execute("DROP INDEX idx_users_id"));
        assertTrue(processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow().indexes().isEmpty());

        assertEquals(
                "ERROR: index does not exist: idx_users_id",
                processor.execute("DROP INDEX idx_users_id")
        );
    }

    @Test
    void executeReturnsOkForUnresolvedInsertUpdateDelete() {
        DefaultQueryProcessor processor = newProcessor();
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
