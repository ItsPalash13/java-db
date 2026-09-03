package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for PRIMARY KEY DDL and DML enforcement.
 * Verifies unique index auto-creation, NOT NULL rejection, duplicate rejection,
 * and durability across restart.
 */
class PrimaryKeyIntegrationTest {

    @TempDir
    Path dataDir;

    @Test
    void createTableWithPrimaryKeyAutoCreatesUniqueIndex() {
        StorageEngine storage = newStorage();
        storage.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(storage);
            assertEquals("OK", p.executeText("CREATE DATABASE shop"));
            assertEquals("OK", p.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            assertEquals("OK", p.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals(
                    List.of(List.of(1, "Ada")),
                    rows(p.execute("SELECT * FROM shop.users"))
            );
        } finally {
            storage.stop();
        }
    }

    @Test
    void insertDuplicatePrimaryKeyRejected() {
        StorageEngine storage = newStorage();
        storage.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(storage);
            assertEquals("OK", p.executeText("CREATE DATABASE shop"));
            assertEquals("OK", p.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            assertEquals("OK", p.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            String result = p.executeText("INSERT INTO shop.users VALUES (1, 'Bob')");
            assertTrue(result.startsWith("ERROR"), "expected error for duplicate PK, got: " + result);
            // Failed unique insert must not leave an orphan heap row.
            assertEquals(1, rows(p.execute("SELECT * FROM shop.users")).size());
        } finally {
            storage.stop();
        }
    }

    @Test
    void insertNullPrimaryKeyRejected() {
        StorageEngine storage = newStorage();
        storage.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(storage);
            assertEquals("OK", p.executeText("CREATE DATABASE shop"));
            assertEquals("OK", p.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            String result = p.executeText("INSERT INTO shop.users VALUES (null, 'Ada')");
            assertTrue(result.startsWith("ERROR"), "expected error for null PK, got: " + result);
        } finally {
            storage.stop();
        }
    }

    @Test
    void updateToNullPrimaryKeyRejected() {
        StorageEngine storage = newStorage();
        storage.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(storage);
            assertEquals("OK", p.executeText("CREATE DATABASE shop"));
            assertEquals("OK", p.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            assertEquals("OK", p.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            String result = p.executeText("UPDATE shop.users SET id = null WHERE id = 1");
            assertTrue(result.startsWith("ERROR"), "expected error for null PK update, got: " + result);
        } finally {
            storage.stop();
        }
    }

    @Test
    void primaryKeySurvivesRestart() {
        Path root = dataDir.resolve("store");

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(first);
            assertEquals("OK", p.executeText("CREATE DATABASE shop"));
            assertEquals("OK", p.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));
            assertEquals("OK", p.executeText("INSERT INTO shop.users VALUES (1, 'Ada')"));
            assertEquals("OK", p.executeText("INSERT INTO shop.users VALUES (2, 'Bob')"));
        } finally {
            first.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor p = new DefaultQueryProcessor(second);
            assertEquals(
                    List.of(List.of(1, "Ada"), List.of(2, "Bob")),
                    rows(p.execute("SELECT * FROM shop.users"))
            );
            String dup = p.executeText("INSERT INTO shop.users VALUES (1, 'Charlie')");
            assertTrue(dup.startsWith("ERROR"),
                    "expected error for duplicate PK after restart, got: " + dup);
        } finally {
            second.stop();
        }
    }

    @Test
    void concurrentDuplicatePrimaryKeyLeavesOnlyOneRow() throws Exception {
        Path root = dataDir.resolve("store-concurrent");
        StorageEngine storage = new DefaultStorageEngine(new DataDirectory(root));
        storage.start();
        try {
            DefaultQueryProcessor setup = new DefaultQueryProcessor(storage);
            assertEquals("OK", setup.executeText("CREATE DATABASE shop"));
            assertEquals("OK", setup.executeText(
                    "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)"));

            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger okInserts = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                new Thread(() -> {
                    DefaultQueryProcessor p = new DefaultQueryProcessor(storage);
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        String result = p.executeText(
                                "INSERT INTO shop.users VALUES (42, 't" + threadId + "')");
                        if (result.startsWith("ERROR")) {
                            errors.incrementAndGet();
                        } else {
                            okInserts.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }, "pk-race-" + t).start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers timed out");

            // Exactly one insert should win; others must ERROR — no orphan heap duplicates.
            assertEquals(1, okInserts.get(), "exactly one INSERT of PK 42 should succeed");
            assertEquals(threads - 1, errors.get(), "remaining inserts should hit unique violation");

            QueryResult all = setup.execute("SELECT * FROM shop.users");
            List<List<Object>> rows = rows(all);
            assertEquals(1, rows.size(), "heap must not retain orphan duplicate PK rows");
            assertEquals(42, rows.get(0).get(0));
        } finally {
            storage.stop();
        }
    }

    private static List<List<Object>> rows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }

    private StorageEngine newStorage() {
        return new DefaultStorageEngine(new DataDirectory(dataDir));
    }
}
