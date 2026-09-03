package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE INDEX builds a physical {@code .idx} tree; equality SELECT works after restart.
 */
class CreateIndexBuildTest {

    @TempDir
    Path dataDir;

    @Test
    void createIndexBuildsTreeAndSelectUsesItAfterRestart() throws Exception {
        Path root = dataDir.resolve("store");
        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (2, 'Bob')"));
            assertEquals("OK", processor.executeText("CREATE INDEX idx_users_id ON shop.users (id)"));
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("idx_users_id.idx")));

            QueryResult select = processor.execute("SELECT name FROM shop.users WHERE id = 1");
            assertEquals(List.of(List.of("Ada")), resultSetRows(select));
        } finally {
            engine.stop();
        }

        StorageEngine restarted = new DefaultStorageEngine(new DataDirectory(root));
        restarted.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(restarted);
            QueryResult select = processor.execute("SELECT name FROM shop.users WHERE id = 2");
            assertEquals(List.of(List.of("Bob")), resultSetRows(select));
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("idx_users_id.idx")));
        } finally {
            restarted.stop();
        }
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
