package com.example.database.processor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;

/**
 * Reader blocked on row S-lock must not return a heap snapshot taken before the lock;
 * after writer ROLLBACK, reader sees restored committed data.
 */
class CascadelessReadIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void readerSeesCommittedValueAfterWriterRollback() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor writer = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor reader = new DefaultQueryProcessor(engine);

        writer.executeText("CREATE DATABASE shop");
        writer.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        writer.executeText("INSERT INTO shop.users VALUES (1, 'Ada')");

        CountDownLatch writerUpdated = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicReference<String> readName = new AtomicReference<>();

        Thread writerThread = new Thread(() -> {
            assertEquals("OK", writer.executeText("BEGIN"));
            assertEquals("OK", writer.executeText("UPDATE shop.users SET name = 'Hidden' WHERE id = 1"));
            writerUpdated.countDown();
            try {
                releaseWriter.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writer.executeText("ROLLBACK");
            }
        });

        Thread readerThread = new Thread(() -> {
            try {
                writerUpdated.await(10, TimeUnit.SECONDS);
                QueryResult result = reader.execute("SELECT name FROM shop.users WHERE id = 1");
                readName.set(resultSetRows(result).get(0).get(0).toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        writerThread.start();
        readerThread.start();
        Thread.sleep(200);
        releaseWriter.countDown();
        readerThread.join(10_000);
        writerThread.join(10_000);

        assertEquals("Ada", readName.get());

        engine.stop();
    }

    @Test
    void deleteDoesNotRemoveRowWhenWhereFailsAfterWriterRollback() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor writer = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor deleter = new DefaultQueryProcessor(engine);

        writer.executeText("CREATE DATABASE shop");
        writer.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        writer.executeText("INSERT INTO shop.users VALUES (1, 'Ada')");

        CountDownLatch writerUpdated = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        Thread writerThread = new Thread(() -> {
            assertEquals("OK", writer.executeText("BEGIN"));
            assertEquals("OK", writer.executeText("UPDATE shop.users SET name = 'Hidden' WHERE id = 1"));
            writerUpdated.countDown();
            try {
                releaseWriter.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writer.executeText("ROLLBACK");
            }
        });

        Thread deleteThread = new Thread(() -> {
            try {
                writerUpdated.await(10, TimeUnit.SECONDS);
                deleter.execute("DELETE FROM shop.users WHERE name = 'Hidden'");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        writerThread.start();
        deleteThread.start();
        Thread.sleep(200);
        releaseWriter.countDown();
        deleteThread.join(10_000);
        writerThread.join(10_000);

        QueryResult result = writer.execute("SELECT name FROM shop.users WHERE id = 1");
        assertEquals("Ada", resultSetRows(result).get(0).get(0).toString());

        engine.stop();
    }

    @Test
    void updateWaitsForRowLockWhenSnapshotWhereWouldMiss() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor writer = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor updater = new DefaultQueryProcessor(engine);

        writer.executeText("CREATE DATABASE shop");
        writer.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        writer.executeText("INSERT INTO shop.users VALUES (1, 'Ada')");

        CountDownLatch writerUpdated = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean updaterFinished = new AtomicBoolean(false);

        Thread writerThread = new Thread(() -> {
            assertEquals("OK", writer.executeText("BEGIN"));
            assertEquals("OK", writer.executeText("UPDATE shop.users SET name = 'Hidden' WHERE id = 1"));
            writerUpdated.countDown();
            try {
                releaseWriter.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writer.executeText("ROLLBACK");
            }
        });

        Thread updaterThread = new Thread(() -> {
            try {
                writerUpdated.await(10, TimeUnit.SECONDS);
                updater.executeText("UPDATE shop.users SET name = 'X' WHERE name = 'Ada'");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                updaterFinished.set(true);
            }
        });

        writerThread.start();
        updaterThread.start();
        assertTrue(writerUpdated.await(10, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertFalse(updaterFinished.get(), "updater must block on row X-lock before writer rollback");

        releaseWriter.countDown();
        updaterThread.join(10_000);
        writerThread.join(10_000);

        assertTrue(updaterFinished.get());
        QueryResult result = writer.execute("SELECT name FROM shop.users WHERE id = 1");
        assertEquals("X", resultSetRows(result).get(0).get(0).toString());

        engine.stop();
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        assertTrue(result.toWireResponse().messages().stream()
                .anyMatch(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance));
        return result.toWireResponse().messages().stream()
                .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
