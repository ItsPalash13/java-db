package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ALTER TABLE ADD COLUMN through SQL must persist and survive process restart.
 */
class AddColumnRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE shop.users (id INT, name VARCHAR)";
    private static final String ADD_AGE = "ALTER TABLE shop.users ADD age INT";

    @TempDir
    Path dataDir;

    @Test
    void addColumnViaSqlSurvivesRestartAndRejectsDuplicate() {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(CREATE_USERS));
            assertEquals("OK", processor.executeText(ADD_AGE));
            assertUsersWithAge(first.catalogManager().getTable("shop", "users").orElseThrow());
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("catalog.json")));
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertUsersWithAge(second.catalogManager().getTable("shop", "users").orElseThrow());

            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals(
                    "ERROR: duplicate column name: age",
                    processor.executeText(ADD_AGE)
            );
        } finally {
            second.stop();
        }
    }

    private static void assertUsersWithAge(TableMetadata users) {
        assertEquals(3, users.columns().size());
        assertEquals("age", users.columns().get(2).name());
        assertEquals(ColumnType.INT, users.columns().get(2).type());
        assertEquals(3, users.columns().get(2).columnId().orElseThrow());
    }
}
