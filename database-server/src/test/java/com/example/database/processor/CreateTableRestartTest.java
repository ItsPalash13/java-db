package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 1.8: CREATE TABLE through SQL must outlive a new StorageEngine on the same directory.
 */
class CreateTableRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE users (id INT, name VARCHAR)";

    @TempDir
    Path dataDir;

    @Test
    void createTableViaSqlSurvivesRestartAndRejectsDuplicate() {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.execute(CREATE_USERS));
            assertUsersTable(first.catalogManager().getTable("users").orElseThrow());
        } finally {
            first.stop();
        }

        // New engine, same files — this is process restart, not catalogManager.load() on the old instance.
        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            assertUsersTable(second.catalogManager().getTable("users").orElseThrow());
            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            assertEquals(
                    "ERROR: table already exists: users",
                    processor.execute(CREATE_USERS)
            );
            assertUsersTable(second.catalogManager().getTable("users").orElseThrow());
        } finally {
            second.stop();
        }
    }

    private static void assertUsersTable(TableMetadata users) {
        assertEquals(1, users.tableId().orElseThrow());
        assertEquals("users", users.name());
        assertEquals(2, users.columns().size());
        assertEquals("id", users.columns().get(0).name());
        assertEquals(ColumnType.INT, users.columns().get(0).type());
        assertEquals(1, users.columns().get(0).columnId().orElseThrow());
        assertEquals("name", users.columns().get(1).name());
        assertEquals(ColumnType.VARCHAR, users.columns().get(1).type());
        assertEquals(2, users.columns().get(1).columnId().orElseThrow());
    }
}
