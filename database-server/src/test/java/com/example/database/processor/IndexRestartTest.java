package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE/DROP INDEX through SQL must persist index definitions and survive restart.
 */
class IndexRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE shop.users (id INT, name VARCHAR)";
    private static final String CREATE_INDEX = "CREATE INDEX idx_users_id ON shop.users (id)";
    private static final String DROP_INDEX = "DROP INDEX idx_users_id";

    @TempDir
    Path dataDir;

    @Test
    void createIndexViaSqlSurvivesRestartAndDropStaysGone() throws Exception {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(CREATE_USERS));
            assertEquals("OK", processor.executeText(CREATE_INDEX));
            assertIndex(first.catalogManager().getTable("shop", "users").orElseThrow());
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("catalog.json")));

            assertEquals("OK", processor.executeText(DROP_INDEX));
            assertTrue(first.catalogManager().getTable("shop", "users").orElseThrow().indexes().isEmpty());
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertTrue(second.catalogManager().getTable("shop", "users").orElseThrow().indexes().isEmpty());

            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals("OK", processor.executeText(CREATE_INDEX));
            assertIndex(second.catalogManager().getTable("shop", "users").orElseThrow());
        } finally {
            second.stop();
        }
    }

    private static void assertIndex(TableMetadata users) {
        assertEquals(1, users.indexes().size());
        IndexMetadata index = users.indexes().get(0);
        assertEquals("idx_users_id", index.name());
        assertEquals(List.of(1), index.columnIds());
    }
}
