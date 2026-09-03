package com.example.database.processor;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitTransactionUndoIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void rollbackDiscardsExplicitUpdate() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);

        processor.executeText("CREATE DATABASE shop");
        processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        processor.executeText("INSERT INTO shop.users VALUES (2, 'two')");

        assertEquals("OK", processor.executeText("BEGIN"));
        assertEquals("OK", processor.executeText("UPDATE shop.users SET name = 't2-r2' WHERE id = 2"));
        assertEquals("OK", processor.executeText("ROLLBACK"));

        QueryResult after = processor.execute("SELECT name FROM shop.users WHERE id = 2");
        assertEquals(List.of(List.of("two")), rows(after));

        engine.stop();
    }

    /**
     * Regression: DELETE undo must re-index the restored row at its new RID.
     * Index* undo with the pre-delete RID left unique lookups empty after ROLLBACK.
     */
    @Test
    void rollbackRestoresDeletedPrimaryKeyRowAndIndex() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir.resolve("pk")));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (7, 'seven')"));

            assertEquals("OK", processor.executeText("BEGIN"));
            assertEquals("OK", processor.executeText("DELETE FROM shop.users WHERE id = 7"));
            assertEquals("OK", processor.executeText("ROLLBACK"));

            QueryResult after = processor.execute("SELECT id, name FROM shop.users WHERE id = 7");
            assertEquals(List.of(List.of(7, "seven")), rows(after));
            // Re-insert must hit unique PK if index was wrongly left populated or cleared.
            assertTrue(processor.executeText("INSERT INTO shop.users VALUES (7, 'dup')")
                    .startsWith("ERROR"));
        } finally {
            engine.stop();
        }
    }

    @Test
    void rollbackCreateIndexDeletesIdxFileSoRecreateSucceeds() throws Exception {
        Path root = tempDir.resolve("idx-rollback");
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("BEGIN"));
            assertEquals("OK", processor.executeText("CREATE INDEX idx_users_name ON shop.users (name)"));
            assertEquals("OK", processor.executeText("ROLLBACK"));
            Path idx = root.resolve("shop").resolve("users").resolve("idx_users_name.idx");
            assertFalse(Files.exists(idx), "ROLLBACK must unlink the new .idx");
            assertEquals("OK", processor.executeText("CREATE INDEX idx_users_name ON shop.users (name)"));
        } finally {
            engine.stop();
        }
    }

    private static List<List<Object>> rows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
