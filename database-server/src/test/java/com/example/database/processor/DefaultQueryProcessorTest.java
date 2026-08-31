package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
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
    void executeSelectWithoutTableIsAnalysisError() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("SELECT * FROM shop.users")
        );
    }

    @Test
    void executeSelectAfterCreateTableReturnsOkFromDeferredExecutor() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
        assertEquals("OK", processor.executeText("SELECT * FROM shop.users"));
    }

    @Test
    void executeReturnsLexErrorWithExactIndex() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.executeText("SELECT @ bad");
        assertEquals("ERROR at index 7: unexpected character '@'", response);
    }

    @Test
    void executeReturnsLexErrorForUnclosedString() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.executeText("INSERT INTO t VALUES ('x");
        assertTrue(response.startsWith("ERROR at index "));
        assertTrue(response.contains("unclosed string literal"));
    }

    @Test
    void executeReturnsParseErrorWithExactIndex() {
        DefaultQueryProcessor processor = newProcessor();
        String response = processor.executeText("CREATE TABLE users");
        assertTrue(response.contains("expected DOT"));
        assertTrue(response.startsWith("ERROR at index "));
    }

    @Test
    void executeCreateTableWritesCatalogAndRejectsDuplicates() {
        DefaultQueryProcessor processor = newProcessor();

        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));

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
                processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeCreateTableWithoutDatabaseIsAnalysisError() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeDropTableRemovesCatalogThenAllowsDropDatabase() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals(
                "ERROR: database is not empty: shop",
                processor.executeText("DROP DATABASE shop")
        );

        assertEquals("OK", processor.executeText("DROP TABLE shop.users"));
        assertFalse(processor.storageEngine().catalogManager().tableExists("shop", "users"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop").resolve("users")));

        assertEquals(
                "ERROR: table does not exist: shop.users",
                processor.executeText("DROP TABLE shop.users")
        );

        assertEquals("OK", processor.executeText("DROP DATABASE shop"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop")));
    }

    @Test
    void executeCreateAndDropDatabase() {
        DefaultQueryProcessor processor = newProcessor();

        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertTrue(processor.storageEngine().catalogManager().databaseExists("shop"));
        assertTrue(Files.isDirectory(dataDir.resolve("shop")));

        assertEquals(
                "ERROR: database already exists: shop",
                processor.executeText("CREATE DATABASE shop")
        );

        assertEquals("OK", processor.executeText("DROP DATABASE shop"));
        assertFalse(processor.storageEngine().catalogManager().databaseExists("shop"));
        assertFalse(Files.isDirectory(dataDir.resolve("shop")));

        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("DROP DATABASE shop")
        );
    }

    @Test
    void executeAddColumnPersistsAndRejectsDuplicate() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.executeText("ALTER TABLE shop.users ADD age INT"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(3, users.columns().size());
        assertEquals("age", users.columns().get(2).name());
        assertEquals(ColumnType.INT, users.columns().get(2).type());
        assertEquals(3, users.columns().get(2).columnId().orElseThrow());

        assertEquals(
                "ERROR: duplicate column name: age",
                processor.executeText("ALTER TABLE shop.users ADD age INT")
        );
    }

    @Test
    void executeDropColumnPersistsAndRejectsMissing() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.executeText("ALTER TABLE shop.users DROP COLUMN name"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(1, users.columns().size());
        assertEquals("id", users.columns().get(0).name());

        assertEquals(
                "ERROR: column does not exist: name",
                processor.executeText("ALTER TABLE shop.users DROP COLUMN name")
        );
    }

    @Test
    void executeCreateAndDropIndexPersistDefinitions() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));

        assertEquals("OK", processor.executeText("CREATE INDEX idx_users_id ON shop.users (id)"));
        TableMetadata users = processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow();
        assertEquals(1, users.indexes().size());
        assertEquals("idx_users_id", users.indexes().get(0).name());
        assertEquals(List.of(1), users.indexes().get(0).columnIds());
        assertFalse(users.indexes().get(0).unique());

        assertEquals(
                "ERROR: index already exists: idx_users_id",
                processor.executeText("CREATE INDEX idx_users_id ON shop.users (name)")
        );

        assertEquals("OK", processor.executeText("DROP INDEX idx_users_id"));
        assertTrue(processor.storageEngine().catalogManager().getTable("shop", "users").orElseThrow().indexes().isEmpty());

        assertEquals(
                "ERROR: index does not exist: idx_users_id",
                processor.executeText("DROP INDEX idx_users_id")
        );
    }

    @Test
    void executeDmlRejectsMissingTableThenAcceptsAfterCreate() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("INSERT INTO shop.items VALUES (1)")
        );
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("UPDATE shop.items SET a = 1")
        );
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("DELETE FROM shop.items")
        );

        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.items (a INT)"));
        assertEquals("OK", processor.executeText("INSERT INTO shop.items VALUES (1)"));
        assertEquals("OK", processor.executeText("UPDATE shop.items SET a = 1"));
        assertEquals("OK", processor.executeText("DELETE FROM shop.items"));
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
                processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)")
        );
    }

    @Test
    void executeDescribeShowReturnsResultSets() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.orders (id INT)"));

        QueryResult describe = processor.execute("DESCRIBE shop.users");
        assertTrue(describe.hasResultSet());
        assertEquals("OK", describe.toResponse());
        assertEquals(
                List.of(
                        List.of("id", "INT", "YES"),
                        List.of("name", "VARCHAR", "YES")
                ),
                describe.toWireResponse().messages().stream()
                        .filter(WireMessage.ResultSet.class::isInstance)
                        .map(WireMessage.ResultSet.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .rows()
        );

        QueryResult descAlias = processor.execute("DESC shop.users");
        assertTrue(descAlias.hasResultSet());

        QueryResult databases = processor.execute("SHOW DATABASES");
        assertTrue(databases.hasResultSet());
        assertEquals(List.of(List.of("shop")), resultSetRows(databases));

        QueryResult tables = processor.execute("SHOW TABLES FROM shop");
        assertTrue(tables.hasResultSet());
        assertEquals(List.of(List.of("orders"), List.of("users")), resultSetRows(tables));
    }

    @Test
    void executeDescribeShowRejectsMissingCatalogObjects() {
        DefaultQueryProcessor processor = newProcessor();
        assertEquals(
                "ERROR: database does not exist: shop",
                processor.executeText("DESCRIBE shop.users")
        );
        assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
        assertEquals(
                "ERROR: table does not exist: shop.users",
                processor.executeText("DESCRIBE shop.users")
        );
        assertEquals(
                "ERROR: database does not exist: missing",
                processor.executeText("SHOW TABLES FROM missing")
        );
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }

    private DefaultQueryProcessor newProcessor() {
        StorageEngine storage = new DefaultStorageEngine(new DataDirectory(dataDir));
        storage.start();
        return new DefaultQueryProcessor(storage);
    }
}
