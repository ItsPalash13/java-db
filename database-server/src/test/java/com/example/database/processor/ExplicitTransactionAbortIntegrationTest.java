package com.example.database.processor;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wait-Die abort during explicit txn must roll back heap rows and end the session
 * so a follow-up COMMIT cannot persist partial work.
 */
class ExplicitTransactionAbortIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void waitDieAbortRollsBackInsertsAndEndsExplicitSession() throws Exception {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor victim = new DefaultQueryProcessor(engine);
        DefaultQueryProcessor holder = new DefaultQueryProcessor(engine);

        victim.executeText("CREATE DATABASE shop");
        victim.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");

        CountDownLatch holderInserted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        Thread holderThread = new Thread(() -> {
            assertEquals("OK", holder.executeText("BEGIN"));
            assertEquals("OK", holder.executeText("INSERT INTO shop.users VALUES (1, 'holder')"));
            holderInserted.countDown();
            try {
                releaseHolder.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                holder.executeText("ROLLBACK");
            }
        });

        holderThread.start();
        assertTrue(holderInserted.await(10, TimeUnit.SECONDS));

        assertEquals("OK", victim.executeText("BEGIN"));
        assertEquals("OK", victim.executeText("INSERT INTO shop.users VALUES (2, 'victim')"));

        QueryResult select = victim.execute("SELECT name FROM shop.users WHERE id = 1");
        assertTrue(select.isError());
        assertTrue(select.toResponse().contains("transaction aborted"));

        assertFalse(engine.transactionManager().inExplicitTransaction());

        QueryResult commitAfterAbort = victim.execute("COMMIT");
        assertTrue(commitAfterAbort.isError());
        assertTrue(commitAfterAbort.toResponse().contains("no explicit transaction"));

        releaseHolder.countDown();
        holderThread.join(10_000);

        // Victim row 2 must be gone; holder rollback removes row 1 — empty heap proves both.
        QueryResult after = victim.execute("SELECT * FROM shop.users");
        assertEquals(List.of(), resultSetRows(after));

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
