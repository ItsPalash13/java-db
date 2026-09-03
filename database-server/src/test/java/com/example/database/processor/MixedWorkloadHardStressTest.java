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
 * Harder than {@link MixedWorkloadLeanStressTest}: more threads/ops, explicit {@code BEGIN}/
 * {@code COMMIT}/{@code ROLLBACK}, and more {@code CHECKPOINT}. Still omits concurrent
 * {@code CREATE INDEX} / column DDL — those take table X and routinely hang the storm under load.
 * <p>
 * Log tags: {@code OK}, {@code ERR_ALLOWED}, {@code ERR_UNEXPECTED}, {@code FATAL}.
 * Overwrites {@code logs/mixed-workload-hard-stress.log}. Tag {@code stress}.
 *
 * <h2>Config</h2>
 * <ul>
 *   <li>{@link #THREADS} = 6 workers</li>
 *   <li>{@link #OPS_PER_THREAD} = 250 (~1500 total)</li>
 *   <li>{@link #SEED} = 99</li>
 *   <li>{@link #SEED_USERS}/{@link #SEED_ORDERS} = 80</li>
 *   <li>{@link #ID_SPACE} = 800</li>
 *   <li>{@link #JOIN_TIMEOUT_SECONDS} = 120</li>
 * </ul>
 *
 * <h2>Op weights ({@link #pickOp} roll 0..99)</h2>
 * <ul>
 *   <li>0–14 (15%): SELECT users</li>
 *   <li>15–29 (15%): SELECT orders (index)</li>
 *   <li>30–44 (15%): INSERT users</li>
 *   <li>45–56 (12%): INSERT orders</li>
 *   <li>57–65 (9%): UPDATE users</li>
 *   <li>66–74 (9%): UPDATE orders</li>
 *   <li>75–79 (5%): DELETE users</li>
 *   <li>80–84 (5%): DELETE orders</li>
 *   <li>85–90 (6%): BEGIN / COMMIT / ROLLBACK</li>
 *   <li>91–94 (4%): CHECKPOINT</li>
 *   <li>95–99 (5%): SHOW / DESCRIBE</li>
 * </ul>
 * Totals: SELECT ~30%, DML ~55%, txn ~6%, CHECKPOINT ~4%, meta ~5%. No concurrent DDL.
 */
@Tag("stress")
class MixedWorkloadHardStressTest {

    private static final int THREADS = 6;
    private static final int OPS_PER_THREAD = 250;
    private static final long SEED = 99L;
    private static final int SEED_USERS = 80;
    private static final int SEED_ORDERS = 80;
    private static final int ID_SPACE = 800;
    private static final long JOIN_TIMEOUT_SECONDS = 120;

    @TempDir
    Path dataDir;

    @Test
    void hardMixedWorkloadSurvivesAndKeepsPrimaryKeysUnique() throws Exception {
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
            logLine(log, "MixedWorkloadHardStressTest started at " + LocalDateTime.now());
            logLine(log, "seed=" + SEED + " threads=" + THREADS + " opsPerThread=" + OPS_PER_THREAD);
            logLine(log, "weights: SELECT~30% DML~55% TXN~6% CHECKPOINT~4% SHOW/DESC~5% (no DDL)");
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
                    // Track explicit txn so BEGIN/COMMIT/ROLLBACK stay coherent for this thread.
                    boolean[] inExplicit = {false};
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < OPS_PER_THREAD && !abort.get(); i++) {
                            String sql = pickOp(rng, inExplicit[0]);
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
                            updateExplicitState(sql, result, inExplicit);
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
                }, "mixed-hard-" + threadId);
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

            // Brief pause so any interrupted lock waiters release ENGINE/table locks.
            Thread.sleep(200);

            DefaultQueryProcessor quiet = new DefaultQueryProcessor(engine);
            quiet.endConnectionSession();
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

    /**
     * After a successful BEGIN, mark explicit; after COMMIT/ROLLBACK (OK or abort-style ERROR), clear.
     */
    private static void updateExplicitState(String sql, String result, boolean[] inExplicit) {
        String upper = sql.trim().toUpperCase();
        boolean ok = !result.startsWith("ERROR");
        if (upper.equals("BEGIN") || upper.startsWith("BEGIN ")) {
            if (ok) {
                inExplicit[0] = true;
            }
            return;
        }
        if (upper.equals("COMMIT") || upper.equals("ROLLBACK")
                || upper.startsWith("COMMIT ") || upper.startsWith("ROLLBACK ")) {
            // Success ends the session; lock abort / refused paths also leave no open txn.
            inExplicit[0] = false;
        }
        if (result.contains("transaction aborted")) {
            inExplicit[0] = false;
        }
    }

    private static boolean isAllowedError(String result) {
        String msg = result == null ? "" : result;
        return msg.contains("duplicate key in unique index")
                || (msg.contains("PRIMARY KEY column") && msg.contains("cannot be null"))
                || msg.contains("lock wait timed out")
                || msg.contains("lock wait interrupted")
                || msg.contains("transaction aborted")
                || msg.contains("CHECKPOINT is not allowed")
                || msg.contains("catalog lock")
                || msg.contains("already exists")
                || msg.contains("duplicate column")
                || msg.contains("does not exist")
                || msg.contains("transaction already active")
                || msg.contains("no explicit transaction")
                || msg.contains("nested transactions")
                || msg.contains("cannot drop")
                || msg.contains("indexed column")
                || msg.contains("last column")
                || msg.contains("index file already exists")
                || (msg.contains("expected") && msg.contains("values but got"))
                || msg.contains("value count");
    }

    /**
     * See class Javadoc for weight table. {@code inExplicit} biases txn ops toward COMMIT/ROLLBACK
     * when a session is open, and toward BEGIN when closed.
     */
    private static String pickOp(Random rng, boolean inExplicit) {
        int roll = rng.nextInt(100);
        int id = 1 + rng.nextInt(ID_SPACE);
        int other = 1 + rng.nextInt(ID_SPACE);

        if (roll < 15) {
            return "SELECT * FROM shop.users WHERE id = " + id;
        }
        if (roll < 30) {
            return "SELECT * FROM shop.orders WHERE user_id = " + (1 + rng.nextInt(SEED_USERS));
        }
        if (roll < 45) {
            return "INSERT INTO shop.users VALUES (" + id + ", 'u" + id + "')";
        }
        if (roll < 57) {
            return "INSERT INTO shop.orders VALUES (" + id + ", " + other + ", " + rng.nextInt(1000) + ")";
        }
        if (roll < 66) {
            return "UPDATE shop.users SET name = 'x" + id + "' WHERE id = " + id;
        }
        if (roll < 75) {
            return "UPDATE shop.orders SET amount = " + rng.nextInt(1000) + " WHERE id = " + id;
        }
        if (roll < 80) {
            return "DELETE FROM shop.users WHERE id = " + id;
        }
        if (roll < 85) {
            return "DELETE FROM shop.orders WHERE id = " + id;
        }
        if (roll < 91) {
            // Stateful txn control: avoid nesting BEGIN when already explicit.
            if (!inExplicit) {
                return "BEGIN";
            }
            return rng.nextBoolean() ? "COMMIT" : "ROLLBACK";
        }
        if (roll < 95) {
            return "CHECKPOINT";
        }
        if (roll < 98) {
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
        if (!result.hasResultSet()) {
            fail("expected result set for " + table + ", got: " + result.toResponse());
        }
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

    /** Repo-root {@code logs/mixed-workload-hard-stress.log}. */
    private static Path stressLogFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("database-server"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            dir = Path.of("").toAbsolutePath();
        }
        return dir.resolve("logs").resolve("mixed-workload-hard-stress.log");
    }
}
