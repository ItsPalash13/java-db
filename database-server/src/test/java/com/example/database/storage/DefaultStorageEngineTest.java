package com.example.database.storage;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStorageEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void startCreatesDataDirectory() {
        Path root = tempDir.resolve("store");
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            assertTrue(Files.isDirectory(engine.dataDirectory().root()));
        } finally {
            engine.stop();
        }
    }

    @Test
    void catalogManagerRequiresStart() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir.resolve("store")));
        assertThrows(IllegalStateException.class, engine::catalogManager);
    }

    @Test
    void createTableSurvivesRestart() {
        Path root = tempDir.resolve("store");
        TableMetadata users = TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        );

        DefaultStorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            CatalogManager catalog = first.catalogManager();
            catalog.createDatabase("shop");
            TableMetadata created = catalog.createTable(users);
            assertEquals(1, created.tableId().orElseThrow());
            assertEquals(ColumnType.INT, created.columns().get(0).type());
            assertEquals(ColumnType.VARCHAR, created.columns().get(1).type());
        } finally {
            first.stop();
        }

        DefaultStorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            TableMetadata loaded = second.catalogManager().getTable("shop", "users").orElseThrow();
            assertEquals(1, loaded.tableId().orElseThrow());
            assertEquals("shop", loaded.database());
            assertEquals("users", loaded.name());
            assertEquals(2, loaded.columns().size());
            assertEquals("id", loaded.columns().get(0).name());
            assertEquals(ColumnType.INT, loaded.columns().get(0).type());
            assertEquals("name", loaded.columns().get(1).name());
            assertEquals(ColumnType.VARCHAR, loaded.columns().get(1).type());
        } finally {
            second.stop();
        }
    }

    @Test
    void createDatabaseSurvivesRestart() {
        Path root = tempDir.resolve("store");

        DefaultStorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            first.catalogManager().createDatabase("shop");
            assertTrue(first.catalogManager().databaseExists("shop"));
            assertTrue(Files.isDirectory(root.resolve("shop")));
        } finally {
            first.stop();
        }

        DefaultStorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertTrue(second.catalogManager().databaseExists("shop"));
            second.catalogManager().dropDatabase("shop");
            assertFalse(Files.isDirectory(root.resolve("shop")));
        } finally {
            second.stop();
        }

        DefaultStorageEngine third = new DefaultStorageEngine(new DataDirectory(root));
        third.start();
        try {
            assertFalse(third.catalogManager().databaseExists("shop"));
        } finally {
            third.stop();
        }
    }
}
