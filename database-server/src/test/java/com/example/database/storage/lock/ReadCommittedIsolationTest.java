package com.example.database.storage.lock;

import com.example.database.processor.DefaultQueryProcessor;
import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadCommittedIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void readYourWritesInExplicitTransaction() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);

        processor.executeText("CREATE DATABASE shop");
        processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");

        assertEquals("OK", processor.executeText("BEGIN"));
        assertEquals("OK", processor.executeText("INSERT INTO shop.users VALUES (1, 'alpha')"));
        QueryResult select = processor.execute("SELECT name FROM shop.users WHERE id = 1");
        assertEquals(List.of(List.of("alpha")), resultSetRows(select));
        assertEquals("OK", processor.executeText("ROLLBACK"));

        engine.stop();
    }

    @Test
    void nonRepeatableReadAllowedBetweenStatements() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor reader = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor writer = new DefaultQueryProcessor(engine);

        reader.executeText("CREATE DATABASE shop");
        reader.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        reader.executeText("INSERT INTO shop.users VALUES (1, 'first')");

        CountDownLatch readerBegun = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);

        Thread readerThread = new Thread(() -> {
            assertEquals("OK", reader.executeText("BEGIN"));
            QueryResult first = reader.execute("SELECT name FROM shop.users WHERE id = 1");
            assertEquals(List.of(List.of("first")), resultSetRows(first));
            readerBegun.countDown();
            try {
                writerDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            QueryResult second = reader.execute("SELECT name FROM shop.users WHERE id = 1");
            assertEquals(List.of(List.of("second")), resultSetRows(second));
            reader.executeText("ROLLBACK");
        });

        readerThread.start();
        assertTrue(readerBegun.await(10, TimeUnit.SECONDS));

        assertEquals("OK", writer.executeText("UPDATE shop.users SET name = 'second' WHERE id = 1"));
        writerDone.countDown();
        readerThread.join(10_000);

        engine.stop();
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
