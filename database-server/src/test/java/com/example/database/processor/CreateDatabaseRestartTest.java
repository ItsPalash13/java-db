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
 * CREATE DATABASE / DROP DATABASE through SQL must outlive a new StorageEngine on the same directory.
 */
class CreateDatabaseRestartTest {

    @TempDir
    Path dataDir;

    @Test
    void createDatabaseViaSqlSurvivesRestartAndDropRemovesIt() {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertTrue(first.catalogManager().databaseExists("shop"));
            assertTrue(Files.isDirectory(root.resolve("shop")));
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertTrue(second.catalogManager().databaseExists("shop"));
            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals(
                    "ERROR: database already exists: shop",
                    processor.executeText("CREATE DATABASE shop")
            );
            assertEquals("OK", processor.executeText("DROP DATABASE shop"));
            assertFalse(second.catalogManager().databaseExists("shop"));
        } finally {
            second.stop();
        }

        StorageEngine third = new DefaultStorageEngine(new DataDirectory(root));
        third.start();
        try {
            assertFalse(third.catalogManager().databaseExists("shop"));
            DefaultQueryProcessor processor = new DefaultQueryProcessor(third);
            assertEquals(
                    "ERROR: database does not exist: shop",
                    processor.executeText("DROP DATABASE shop")
            );
        } finally {
            third.stop();
        }
    }
}
