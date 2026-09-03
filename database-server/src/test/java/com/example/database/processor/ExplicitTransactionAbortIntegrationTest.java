package com.example.database.processor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;

/**
 * Deadlock during explicit txn must undo heap rows and end the session
 * so a follow-up COMMIT cannot persist partial work.
 * <p>
 * Requires an index on {@code id} so each UPDATE locks only the matching row.
 * A table-scan UPDATE locks every scanned row before WHERE, so T2's
 * {@code UPDATE ... WHERE id = 2} would block on T1's row-1 lock and never
 * create the classic cross-lock deadlock this test needs.
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
        // Point UPDATEs must use INDEX_SCAN; otherwise table scan locks non-matching rows first.
        t1Client.executeText("CREATE INDEX idx_users_id ON shop.users (id)");

        CountDownLatch t1HasRow1 = new CountDownLatch(1);
        CountDownLatch t2HasRow2 = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        AtomicReference<QueryResult> t2Result = new AtomicReference<>();
        AtomicReference<QueryResult> t2CommitAfterAbort = new AtomicReference<>();
        AtomicReference<Throwable> t2Failure = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            assertEquals("OK", t1Client.executeText("BEGIN"));
            assertEquals("OK", t1Client.executeText("UPDATE shop.users SET name = 't1-r1' WHERE id = 1"));
            t1HasRow1.countDown();
            try {
                assertTrue(t2HasRow2.await(10, TimeUnit.SECONDS), "T2 never locked row 2");
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
        }, "deadlock-t1");

        Thread t2 = new Thread(() -> {
            try {
                assertTrue(t1HasRow1.await(10, TimeUnit.SECONDS), "T1 never locked row 1");
                assertEquals("OK", t2Client.executeText("BEGIN"));
                assertEquals("OK", t2Client.executeText("UPDATE shop.users SET name = 't2-r2' WHERE id = 2"));
                t2HasRow2.countDown();
                // Cross lock: wait for row 1 while T1 waits for row 2 → deadlock abort.
                t2Result.set(t2Client.execute("UPDATE shop.users SET name = 't2-r1' WHERE id = 1"));
                // Same thread owns the ThreadLocal txn context — COMMIT must run here, not on main.
                t2CommitAfterAbort.set(t2Client.execute("COMMIT"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                t2Failure.set(t);
            }
        }, "deadlock-t2");

        t1.start();
        t2.start();
        t2.join(15_000);
        assertFalse(t2.isAlive(), "T2 still blocked; deadlock was not resolved");
        assertEquals(null, t2Failure.get(), () -> "T2 failed: " + t2Failure.get());

        QueryResult abortResult = t2Result.get();
        assertNotNull(abortResult, "expected deadlock abort on T2");
        assertTrue(abortResult.isError());
        assertTrue(abortResult.toResponse().contains("transaction aborted"));

        QueryResult commitAfterAbort = t2CommitAfterAbort.get();
        assertNotNull(commitAfterAbort);
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
