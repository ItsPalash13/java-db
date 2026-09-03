package com.example.database.processor;

import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENGINE X on CHECKPOINT must queue concurrent ENGINE IS (SELECT) and ENGINE IX (DML/DDL).
 * Holders are simulated with {@link LockManager} when mid-statement SQL is hard to pause.
 */
class EngineLockCheckpointIntegrationTest {

    @TempDir
    Path tempDir;

    private DefaultStorageEngine engine;
    private DefaultQueryProcessor client;
    private DefaultQueryProcessor other;

    @BeforeEach
    void setUp() {
        engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        client = new DefaultQueryProcessor(engine);
        other = new DefaultQueryProcessor(engine);
        client.executeText("CREATE DATABASE shop");
        client.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
        client.executeText("CREATE TABLE shop.orders (id INT, amount INT)");
        client.executeText("INSERT INTO shop.users VALUES (1, 'ada')");
        client.executeText("INSERT INTO shop.orders VALUES (1, 10)");
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void checkpointWaitsForInFlightEngineIxThenCompletes() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean checkpointDone = new AtomicBoolean(false);
        AtomicReference<String> checkpointResponse = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            locks.bindOwner(9_001L);
            try {
                locks.lockEngine(LockMode.IX);
                holderReady.countDown();
                releaseHolder.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlockEngine(LockMode.IX);
                locks.clearOwnerBinding();
            }
        }, "engine-ix-holder");

        Thread checkpoint = new Thread(() -> {
            try {
                assertTrue(holderReady.await(5, TimeUnit.SECONDS));
                checkpointResponse.set(other.executeText("CHECKPOINT"));
                checkpointDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "checkpoint-waiter");

        holder.start();
        assertTrue(holderReady.await(5, TimeUnit.SECONDS));
        checkpoint.start();
        Thread.sleep(200);
        assertFalse(checkpointDone.get(), "CHECKPOINT must wait on ENGINE IX");
        releaseHolder.countDown();
        checkpoint.join(10_000);
        holder.join(10_000);
        assertEquals("OK", checkpointResponse.get());
        assertTrue(checkpointDone.get());
    }

    @Test
    void insertBlocksWhileCheckpointHoldsEngineX() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch checkpointReady = new CountDownLatch(1);
        CountDownLatch releaseCheckpoint = new CountDownLatch(1);
        AtomicBoolean insertDone = new AtomicBoolean(false);
        AtomicReference<String> insertResponse = new AtomicReference<>();

        Thread checkpoint = new Thread(() -> {
            locks.runWithEngineX(() -> {
                checkpointReady.countDown();
                try {
                    releaseCheckpoint.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }, "engine-x-holder");

        Thread insert = new Thread(() -> {
            try {
                assertTrue(checkpointReady.await(5, TimeUnit.SECONDS));
                insertResponse.set(other.executeText("INSERT INTO shop.users VALUES (2, 'bob')"));
                insertDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "insert-waiter");

        checkpoint.start();
        assertTrue(checkpointReady.await(5, TimeUnit.SECONDS));
        insert.start();
        Thread.sleep(200);
        assertFalse(insertDone.get(), "INSERT must wait on ENGINE X");
        releaseCheckpoint.countDown();
        insert.join(10_000);
        checkpoint.join(10_000);
        assertEquals("OK", insertResponse.get());
        assertTrue(insertDone.get());
    }

    @Test
    void selectEngineIsBlocksCheckpointUntilReleased() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean checkpointDone = new AtomicBoolean(false);

        Thread holder = new Thread(() -> {
            locks.bindOwner(9_002L);
            try {
                locks.lockEngine(LockMode.IS);
                holderReady.countDown();
                releaseHolder.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlockEngine(LockMode.IS);
                locks.clearOwnerBinding();
            }
        }, "engine-is-holder");

        Thread checkpoint = new Thread(() -> {
            try {
                assertTrue(holderReady.await(5, TimeUnit.SECONDS));
                other.executeText("CHECKPOINT");
                checkpointDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "checkpoint-after-select");

        holder.start();
        assertTrue(holderReady.await(5, TimeUnit.SECONDS));
        checkpoint.start();
        Thread.sleep(200);
        assertFalse(checkpointDone.get());
        releaseHolder.countDown();
        checkpoint.join(10_000);
        holder.join(10_000);
        assertTrue(checkpointDone.get());
    }

    @Test
    void createIndexEngineIxBlocksCheckpoint() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean checkpointDone = new AtomicBoolean(false);

        // CREATE INDEX takes ENGINE IX + table X; hold ENGINE IX to stand in for mid-DDL.
        Thread holder = new Thread(() -> {
            locks.bindOwner(9_003L);
            try {
                locks.lockEngine(LockMode.IX);
                holderReady.countDown();
                releaseHolder.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlockEngine(LockMode.IX);
                locks.clearOwnerBinding();
            }
        }, "ddl-ix-holder");

        Thread checkpoint = new Thread(() -> {
            try {
                assertTrue(holderReady.await(5, TimeUnit.SECONDS));
                other.executeText("CHECKPOINT");
                checkpointDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "checkpoint-after-ddl");

        holder.start();
        assertTrue(holderReady.await(5, TimeUnit.SECONDS));
        checkpoint.start();
        Thread.sleep(200);
        assertFalse(checkpointDone.get());
        releaseHolder.countDown();
        checkpoint.join(10_000);
        holder.join(10_000);
        assertTrue(checkpointDone.get());
        assertEquals("OK", client.executeText("CREATE INDEX idx_users_id ON shop.users (id)"));
    }

    @Test
    void checkpointQueuesCreateTable() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch checkpointReady = new CountDownLatch(1);
        CountDownLatch releaseCheckpoint = new CountDownLatch(1);
        AtomicBoolean createDone = new AtomicBoolean(false);
        AtomicReference<String> createResponse = new AtomicReference<>();

        Thread checkpoint = new Thread(() -> {
            locks.runWithEngineX(() -> {
                checkpointReady.countDown();
                try {
                    releaseCheckpoint.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }, "engine-x-for-ddl");

        Thread create = new Thread(() -> {
            try {
                assertTrue(checkpointReady.await(5, TimeUnit.SECONDS));
                createResponse.set(other.executeText("CREATE TABLE shop.items (id INT)"));
                createDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "create-table-waiter");

        checkpoint.start();
        assertTrue(checkpointReady.await(5, TimeUnit.SECONDS));
        create.start();
        Thread.sleep(200);
        assertFalse(createDone.get(), "CREATE TABLE must wait on ENGINE X");
        releaseCheckpoint.countDown();
        create.join(10_000);
        checkpoint.join(10_000);
        assertEquals("OK", createResponse.get());
    }

    @Test
    void tableDdlOnUsersAllowsDmlOnOrders() throws Exception {
        LockManager locks = engine.lockManager();
        CountDownLatch ddlReady = new CountDownLatch(1);
        CountDownLatch releaseDdl = new CountDownLatch(1);

        Thread ddl = new Thread(() -> {
            locks.bindOwner(9_004L);
            try {
                // Same as CommandExecutor: ENGINE IX then table X on users.
                locks.lockEngine(LockMode.IX);
                locks.lockTable("shop", "users", LockMode.X);
                ddlReady.countDown();
                releaseDdl.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlockTable("shop", "users", LockMode.X);
                locks.unlockEngine(LockMode.IX);
                locks.clearOwnerBinding();
            }
        }, "users-ddl");

        ddl.start();
        assertTrue(ddlReady.await(5, TimeUnit.SECONDS));
        assertEquals("OK", other.executeText("INSERT INTO shop.orders VALUES (2, 20)"));
        releaseDdl.countDown();
        ddl.join(10_000);
    }

    @Test
    void twoSelectsConcurrentBothEngineIsOk() {
        QueryResult a = client.execute("SELECT id, name FROM shop.users");
        QueryResult b = other.execute("SELECT id, amount FROM shop.orders");
        assertFalse(a.isError());
        assertFalse(b.isError());
    }

    @Test
    void checkpointRefusedWhileExplicitBeginOpen() {
        assertEquals("OK", client.executeText("BEGIN"));
        QueryResult result = other.execute("CHECKPOINT");
        assertTrue(result.isError());
        assertTrue(result.toResponse().contains("explicit"));
        assertEquals("OK", client.executeText("ROLLBACK"));
    }
}
