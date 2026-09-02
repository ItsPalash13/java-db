package com.example.database.processor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Deadlock during explicit txn must undo heap rows and end the session
 * so a follow-up COMMIT cannot persist partial work.
 */
class ExplicitTransactionAbortIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void deadlockAbortRollsBackUpdatesAndEndsExplicitSession() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor t1Client = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor t2Client = new DefaultQueryProcessor(engine);

        t1Client.executeText("CREATE DATABASE shop");
        t1Client.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        t1Client.executeText("INSERT INTO shop.users VALUES (1, 'one')");
        t1Client.executeText("INSERT INTO shop.users VALUES (2, 'two')");

        CountDownLatch t1HasRow1 = new CountDownLatch(1);
        CountDownLatch t2HasRow2 = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        AtomicReference<QueryResult> t2Result = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            assertEquals("OK", t1Client.executeText("BEGIN"));
            assertEquals("OK", t1Client.executeText("UPDATE shop.users SET name = 't1-r1' WHERE id = 1"));
            t1HasRow1.countDown();
            try {
                t2HasRow2.await(10, TimeUnit.SECONDS);
                t1Client.execute("UPDATE shop.users SET name = 't1-r2' WHERE id = 2");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    releaseT1.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                t1Client.executeText("ROLLBACK");
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                t1HasRow1.await(10, TimeUnit.SECONDS);
                assertEquals("OK", t2Client.executeText("BEGIN"));
                assertEquals("OK", t2Client.executeText("UPDATE shop.users SET name = 't2-r2' WHERE id = 2"));
                t2HasRow2.countDown();
                t2Result.set(t2Client.execute("UPDATE shop.users SET name = 't2-r1' WHERE id = 1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        t1.start();
        t2.start();
        t2.join(10_000);

        QueryResult abortResult = t2Result.get();
        assertTrue(abortResult.isError());
        assertTrue(abortResult.toResponse().contains("transaction aborted"));

        assertFalse(engine.transactionManager().inExplicitTransaction());

        QueryResult commitAfterAbort = t2Client.execute("COMMIT");
        assertTrue(commitAfterAbort.isError());

        releaseT1.countDown();
        t1.join(10_000);

        QueryResult after = t1Client.execute("SELECT id, name FROM shop.users");
        List<List<Object>> rows = new java.util.ArrayList<>(resultSetRows(after));
        rows.sort((a, b) -> Integer.compare((Integer) a.get(0), (Integer) b.get(0)));
        assertEquals(List.of(
                List.of(1, "one"),
                List.of(2, "two")
        ), rows);

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
