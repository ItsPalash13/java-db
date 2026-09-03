package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DML rows must survive process restart once dirty heap pages are flushed on stop.
 */
class DmlRestartTest {

    private static final String CREATE_USERS = "CREATE TABLE shop.users (id INT, name VARCHAR)";

    @TempDir
    Path dataDir;

    @Test
    void insertSelectSurvivesRestart() throws Exception {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(CREATE_USERS));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (2, 'Bob')"));
            assertTrue(Files.isRegularFile(root.resolve("shop").resolve("users").resolve("users.ibd")));
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(second);
            QueryResult result = processor.execute("SELECT id, name FROM shop.users");
            assertTrue(result.hasResultSet());
            assertEquals(
                    List.of(List.of(1, "Ada"), List.of(2, "Bob")),
                    resultSetRows(result)
            );
        } finally {
            second.stop();
        }
    }

    @Test
    void dropTableRemovesIbdFile() throws Exception {
        Path root = dataDir.resolve("store");

        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            assertEquals("OK", processor.executeText("CREATE DATABASE shop"));
            assertEquals("OK", processor.executeText(CREATE_USERS));
            assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            Path ibd = root.resolve("shop").resolve("users").resolve("users.ibd");
            assertTrue(Files.isRegularFile(ibd));

            assertEquals("OK", processor.executeText("DROP TABLE shop.users"));
            assertTrue(Files.notExists(ibd));
        } finally {
            engine.stop();
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
