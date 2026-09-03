package com.example.database.processor;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.wal.DefaultWALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6: committed DML survives when WAL was flushed but dirty {@code .ibd} pages were not.
 */
class DmlWalRecoveryTest {

    @TempDir
    Path dataDir;

    @Test
    void commitThenCrashWithoutHeapPagesRecoversRowsFromWal() throws Exception {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (2, 'Bob')"));
            first.walManager().flush();
        } finally {
            first.stop();
        }

        // Crash simulation: durable WAL kept; discard on-disk heap so restart must redo.
        Path ibd = root.resolve("shop").resolve("users").resolve("users.ibd");
        assertTrue(Files.isRegularFile(ibd));
        Files.delete(ibd);

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            QueryResult result = processor.execute("SELECT id, name FROM shop.users");
            assertFalse(result.isError());
            List<List<Object>> rows = new ArrayList<>(resultSetRows(result));
            rows.sort((a, b) -> Integer.compare((Integer) a.get(0), (Integer) b.get(0)));
            assertEquals(List.of(List.of(1, "Ada"), List.of(2, "Bob")), rows);
        } finally {
            second.stop();
        }
    }

    @Test
    void rollbackThenRestartLeavesNoRow() throws Exception {
        Path root = dataDir.resolve("rb");
        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
            assertEquals("OK", processor.executeText("BEGIN"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("ROLLBACK"));
        } finally {
            engine.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            QueryResult result = processor.execute("SELECT id, name FROM shop.users");
            assertFalse(result.isError());
            assertTrue(resultSetRows(result).isEmpty());
        } finally {
            second.stop();
        }
    }

    @Test
    void checkpointFlushesDirtyPagesAndAdvancesFence() throws Exception {
        Path root = dataDir.resolve("ckpt");
        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("CHECKPOINT"));
            Path wal = root.resolve(DefaultWALManager.WAL_FILE);
            String text = Files.readString(wal, StandardCharsets.UTF_8);
            assertTrue(text.contains("INSERT_ROW"));
            assertTrue(text.contains("\"op\":\"CHECKPOINT\""));
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("users.ibd")));
            assertTrue(Files.isRegularFile(root.resolve(DefaultWALManager.CHECKPOINT_FILE)));
        } finally {
            engine.stop();
        }
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(m -> m instanceof com.example.database.network.wire.WireMessage.ResultSet)
                .map(m -> ((com.example.database.network.wire.WireMessage.ResultSet) m).rows())
                .findFirst()
                .orElseThrow();
    }
}
