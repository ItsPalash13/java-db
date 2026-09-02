package com.example.database.processor;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static List<List<Object>> rows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
