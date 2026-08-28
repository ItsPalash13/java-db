package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ALTER TABLE DROP COLUMN through SQL must persist and survive process restart.
 */
class DropColumnRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE shop.users (id INT, name VARCHAR)";
    private static final String DROP_NAME = "ALTER TABLE shop.users DROP COLUMN name";

    @TempDir
    Path dataDir;

    @Test
    void dropColumnViaSqlSurvivesRestartAndRejectsMissing() {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.execute("CREATE DATABASE shop"));
            assertEquals("OK", processor.execute(CREATE_USERS));
            assertEquals("OK", processor.execute(DROP_NAME));
            assertUsersWithoutName(first.catalogManager().getTable("shop", "users").orElseThrow());
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("catalog.json")));
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertUsersWithoutName(second.catalogManager().getTable("shop", "users").orElseThrow());

            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals(
                    "ERROR: column does not exist: name",
                    processor.execute(DROP_NAME)
            );
        } finally {
            second.stop();
        }
    }

    private static void assertUsersWithoutName(TableMetadata users) {
        assertEquals(1, users.columns().size());
        assertEquals("id", users.columns().get(0).name());
        assertEquals(1, users.columns().get(0).columnId().orElseThrow());
    }
}
