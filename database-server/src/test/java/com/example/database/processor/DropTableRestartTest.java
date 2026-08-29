package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP TABLE through SQL must delete the per-table catalog and stay gone after restart.
 */
class DropTableRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE shop.users (id INT, name VARCHAR)";
    private static final String DROP_USERS = "DROP TABLE shop.users";

    @TempDir
    Path dataDir;

    @Test
    void dropTableViaSqlSurvivesRestartAndRejectsSecondDrop() throws Exception {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(CREATE_USERS));
            assertTrue(first.catalogManager().tableExists("shop", "users"));
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("catalog.json")));

            assertEquals("OK", processor.executeText(DROP_USERS));
            assertFalse(first.catalogManager().tableExists("shop", "users"));
            assertFalse(Files.isDirectory(root.resolve("shop").resolve("users")));
        } finally {
            first.stop();
        }

        // New engine, same files — table must still be absent after process restart.
        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertFalse(second.catalogManager().tableExists("shop", "users"));
            assertFalse(Files.isDirectory(root.resolve("shop").resolve("users")));
            assertTrue(Files.isDirectory(root.resolve("shop")));

            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals(
                    "ERROR: table does not exist: shop.users",
                    processor.executeText(DROP_USERS)
            );
        } finally {
            second.stop();
        }
    }
}
