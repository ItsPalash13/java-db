package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lean concurrent mixed-workload stress: survivability harness, not a correctness proof.
 * <p>
 * Workers share one {@link StorageEngine}, each with its own {@link DefaultQueryProcessor}
 * (txn state is ThreadLocal). DML/SELECT heavy; no DDL and no explicit {@code BEGIN}.
 * See {@link MixedWorkloadHardStressTest} for a harsher variant.
 * <p>
 * Log tags: {@code OK}, {@code ERR_ALLOWED}, {@code ERR_UNEXPECTED}, {@code FATAL}.
 * Overwrites {@code logs/mixed-workload-lean-stress.log}. Tag {@code stress} so CI can skip.
 *
 * <h2>Config</h2>
 * <ul>
 *   <li>{@link #THREADS} = 4 concurrent workers</li>
 *   <li>{@link #OPS_PER_THREAD} = 200 statements each (800 total)</li>
 *   <li>{@link #SEED} = 42 — op streams reproducible per thread; JVM scheduling is not</li>
 *   <li>{@link #SEED_USERS}/{@link #SEED_ORDERS} = 50 seed rows each</li>
 *   <li>{@link #ID_SPACE} = 500 — random PK/id domain (collisions → ERR_ALLOWED)</li>
 *   <li>{@link #JOIN_TIMEOUT_SECONDS} = 90 — hang detection</li>
 * </ul>
 *
 * <h2>Op weights ({@link #pickOp} roll 0..99)</h2>
 * <ul>
 *   <li>0–19 (20%): SELECT users by id</li>
 *   <li>20–39 (20%): SELECT orders by user_id (index)</li>
 *   <li>40–54 (15%): INSERT users</li>
 *   <li>55–64 (10%): INSERT orders</li>
 *   <li>65–74 (10%): UPDATE users</li>
 *   <li>75–84 (10%): UPDATE orders</li>
 *   <li>85–89 (5%): DELETE users</li>
 *   <li>90–94 (5%): DELETE orders</li>
 *   <li>95–96 (2%): CHECKPOINT</li>
 *   <li>97–98 (2%): SHOW TABLES</li>
 *   <li>99 (1%): DESCRIBE</li>
 * </ul>
 * Totals: SELECT ~40%, INSERT ~25%, UPDATE ~20%, DELETE ~10%, control ~5%.
 */
@Tag("stress")
class MixedWorkloadLeanStressTest {

    /** Concurrent workers sharing one engine. */
    private static final int THREADS = 4;
    /** Statements each worker runs after the start latch. */
    private static final int OPS_PER_THREAD = 200;
    /** Base RNG seed; worker t uses {@code SEED + t}. */
    private static final long SEED = 42L;
    /** Rows inserted into shop.users before the storm. */
    private static final int SEED_USERS = 50;
    /** Rows inserted into shop.orders before the storm. */
    private static final int SEED_ORDERS = 50;
    /** Random id domain for INSERT/UPDATE/DELETE/SELECT probes (collisions expected). */
    private static final int ID_SPACE = 500;
    /** Fail the test if workers have not finished by then (likely hang). */
    private static final long JOIN_TIMEOUT_SECONDS = 90;

    @TempDir
    Path dataDir;

    @Test
    void mixedWorkloadSurvivesAndKeepsPrimaryKeysUnique() throws Exception {
        Path logFile = stressLogFile();
        Files.createDirectories(logFile.getParent());
        Path root = dataDir.resolve("store");

        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger allowedErrorCount = new AtomicInteger();
        AtomicInteger unexpectedErrorCount = new AtomicInteger();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        AtomicReference<String> unexpectedErrorSample = new AtomicReference<>();
        AtomicBoolean abort = new AtomicBoolean(false);

        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try (BufferedWriter log = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            logLine(log, "MixedWorkloadLeanStressTest started at " + LocalDateTime.now());
            logLine(log, "seed=" + SEED + " threads=" + THREADS + " opsPerThread=" + OPS_PER_THREAD);
            logLine(log, "weights: SELECT~40% INSERT~25% UPDATE~20% DELETE~10% CHECKPOINT~2% SHOW/DESC~3%");
            logLine(log, "tags: OK | ERR_ALLOWED | ERR_UNEXPECTED | FATAL");
            logLine(log, "log file: " + logFile.toAbsolutePath());

            DefaultQueryProcessor setup = new DefaultQueryProcessor(engine);
            setupSchema(setup, log);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            List<Thread> workers = new ArrayList<>(THREADS);

            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                Random rng = new Random(SEED + threadId);
                Thread worker = new Thread(() -> {
                    DefaultQueryProcessor client = new DefaultQueryProcessor(engine);
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < OPS_PER_THREAD && !abort.get(); i++) {
                            String sql = pickOp(rng);
                            long t0 = System.nanoTime();
                            String result;
                            try {
                                result = client.executeText(sql);
                            } catch (Throwable e) {
                                fatal.compareAndSet(null, e);
                                abort.set(true);
                                logLineSync(log, "FATAL t=" + threadId + " op=" + i + " sql=" + sql
                                        + " ex=" + e);
                                break;
                            }
                            long ms = (System.nanoTime() - t0) / 1_000_000L;
                            if (result.startsWith("ERROR")) {
                                if (isAllowedError(result)) {
                                    allowedErrorCount.incrementAndGet();
                                    logLineSync(log, "ERR_ALLOWED t=" + threadId + " op=" + i + " "
                                            + ms + "ms " + sql + " => " + result);
                                } else {
                                    unexpectedErrorCount.incrementAndGet();
                                    unexpectedErrorSample.compareAndSet(null, result + " | sql=" + sql);
                                    abort.set(true);
                                    logLineSync(log, "ERR_UNEXPECTED t=" + threadId + " op=" + i + " "
                                            + ms + "ms " + sql + " => " + result);
                                    break;
                                }
                            } else {
                                okCount.incrementAndGet();
                                logLineSync(log, "OK   t=" + threadId + " op=" + i + " " + ms + "ms "
                                        + sql + " => " + truncate(result));
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fatal.compareAndSet(null, e);
                        abort.set(true);
                    } finally {
                        try {
                            client.endConnectionSession();
                        } catch (Throwable ignored) {
                            // best-effort cleanup
                        }
                        done.countDown();
                    }
                }, "mixed-lean-" + threadId);
                workers.add(worker);
                worker.start();
            }

            start.countDown();
            boolean finished = done.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                abort.set(true);
                for (Thread w : workers) {
                    w.interrupt();
                }
                fail("workers did not finish within " + JOIN_TIMEOUT_SECONDS + "s — likely hang");
            }

            if (fatal.get() != null) {
                fail("worker hit unexpected exception: " + fatal.get(), fatal.get());
            }
            if (unexpectedErrorCount.get() > 0) {
                fail("worker hit ERR_UNEXPECTED: " + unexpectedErrorSample.get());
            }

            logLine(log, "storm done ok=" + okCount.get()
                    + " err_allowed=" + allowedErrorCount.get()
                    + " err_unexpected=" + unexpectedErrorCount.get());

            DefaultQueryProcessor quiet = new DefaultQueryProcessor(engine);
            assertPrimaryKeysUnique(quiet, "shop.users", log);
            assertPrimaryKeysUnique(quiet, "shop.orders", log);
            String checkpoint = quiet.executeText("CHECKPOINT");
            logLine(log, "post-storm CHECKPOINT => " + checkpoint);
            assertTrue(checkpoint.equals("OK") || checkpoint.startsWith("ERROR"),
                    "CHECKPOINT should return OK or ERROR, got: " + checkpoint);

            engine.stop();
        }

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor after = new DefaultQueryProcessor(second);
            try (BufferedWriter log = Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            )) {
                logLine(log, "restart validation at " + LocalDateTime.now());
                assertPrimaryKeysUnique(after, "shop.users", log);
                assertPrimaryKeysUnique(after, "shop.orders", log);
                QueryResult users = after.execute("SELECT * FROM shop.users");
                QueryResult orders = after.execute("SELECT * FROM shop.orders");
                assertTrue(users.hasResultSet(), "users should be selectable after restart");
                assertTrue(orders.hasResultSet(), "orders should be selectable after restart");
                logLine(log, "restart users rows=" + resultSetRows(users).size()
                        + " orders rows=" + resultSetRows(orders).size());
                logLine(log, "PASS");
            }
        } finally {
            second.stop();
        }
    }

    private void setupSchema(DefaultQueryProcessor client, BufferedWriter log) throws IOException {
        run(client, log, "CREATE DATABASE shop");
        run(client, log, "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)");
        run(client, log, "CREATE TABLE shop.orders (id INT PRIMARY KEY, user_id INT, amount INT)");
        run(client, log, "CREATE INDEX idx_orders_user ON shop.orders (user_id)");
        for (int i = 1; i <= SEED_USERS; i++) {
            run(client, log, "INSERT INTO shop.users VALUES (" + i + ", 'user" + i + "')");
        }
        for (int i = 1; i <= SEED_ORDERS; i++) {
            int userId = ((i - 1) % SEED_USERS) + 1;
            run(client, log, "INSERT INTO shop.orders VALUES (" + i + ", " + userId + ", " + (i * 10) + ")");
        }
    }

    private static boolean isAllowedError(String result) {
        String msg = result == null ? "" : result;
        return msg.contains("duplicate key in unique index")
                || (msg.contains("PRIMARY KEY column") && msg.contains("cannot be null"))
                || msg.contains("lock wait timed out")
                || msg.contains("transaction aborted")
                || msg.contains("CHECKPOINT is not allowed")
                || msg.contains("catalog lock");
    }

    /**
     * See class Javadoc for weight table. Roll is uniform in {@code [0, 100)}.
     */
    private static String pickOp(Random rng) {
        int roll = rng.nextInt(100);
        int id = 1 + rng.nextInt(ID_SPACE);
        int other = 1 + rng.nextInt(ID_SPACE);
        if (roll < 20) {
            return "SELECT * FROM shop.users WHERE id = " + id;
        }
        if (roll < 40) {
            return "SELECT * FROM shop.orders WHERE user_id = " + (1 + rng.nextInt(SEED_USERS));
        }
        if (roll < 55) {
            return "INSERT INTO shop.users VALUES (" + id + ", 'u" + id + "')";
        }
        if (roll < 65) {
            return "INSERT INTO shop.orders VALUES (" + id + ", " + other + ", " + rng.nextInt(1000) + ")";
        }
        if (roll < 75) {
            return "UPDATE shop.users SET name = 'x" + id + "' WHERE id = " + id;
        }
        if (roll < 85) {
            return "UPDATE shop.orders SET amount = " + rng.nextInt(1000) + " WHERE id = " + id;
        }
        if (roll < 90) {
            return "DELETE FROM shop.users WHERE id = " + id;
        }
        if (roll < 95) {
            return "DELETE FROM shop.orders WHERE id = " + id;
        }
        if (roll < 97) {
            return "CHECKPOINT";
        }
        if (roll < 99) {
            return "SHOW TABLES FROM shop";
        }
        return "DESCRIBE shop.users";
    }

    private static void assertPrimaryKeysUnique(
            DefaultQueryProcessor client,
            String table,
            BufferedWriter log
    ) throws IOException {
        QueryResult result = client.execute("SELECT * FROM " + table);
        assertTrue(result.hasResultSet(), "expected result set for " + table);
        List<List<Object>> rows = resultSetRows(result);
        Set<Object> seen = new HashSet<>();
        for (List<Object> row : rows) {
            assertFalse(row.isEmpty(), "row missing id column in " + table);
            Object pk = row.get(0);
            assertFalse(seen.contains(pk), "duplicate PRIMARY KEY " + pk + " in " + table);
            seen.add(pk);
        }
        logLine(log, "PK unique " + table + " rows=" + rows.size());
    }

    private static void run(DefaultQueryProcessor client, BufferedWriter log, String sql)
            throws IOException {
        String result = client.executeText(sql);
        logLine(log, "SETUP " + sql + " => " + result);
        assertTrue(result.equals("OK") || result.startsWith("OK"),
                "setup failed: " + sql + " => " + result);
    }

    private static synchronized void logLineSync(BufferedWriter log, String line) {
        try {
            logLine(log, line);
            log.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void logLine(BufferedWriter log, String line) throws IOException {
        log.write(line);
        log.newLine();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }

    /** Repo-root {@code logs/mixed-workload-lean-stress.log}. */
    private static Path stressLogFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("database-server"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            dir = Path.of("").toAbsolutePath();
        }
        return dir.resolve("logs").resolve("mixed-workload-lean-stress.log");
    }
}
