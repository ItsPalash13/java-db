package com.example.client;

import com.example.client.wire.WireMessage;
import com.example.client.wire.WireResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v1 multi-client mixed workload + oracle for {@code test-scripts/mixed_crash_page_graph.sh}.
 * <p>
 * Roles: one DDL/CHECKPOINT client + three DML/DQL clients. Mix targets roughly
 * DDL ~10% / DML ~70% / DQL ~20% across all ops (CHECKPOINT injected by the DDL client).
 * Auto-commit only — oracle updates only on wire OK.
 *
 * <pre>
 * MixedCrashHarness load       host port seed oracleDir [totalOps]
 * MixedCrashHarness verify     host port seed oracleDir
 * MixedCrashHarness checkpoint host port
 * </pre>
 */
public final class MixedCrashHarness {

    private static final int DEFAULT_OPS = 10000;
    private static final int WORKER_CLIENTS = 3;
    /** Per-client ops between a full pause + CHECKPOINT (avoids ENGINE X vs IX deadlock). */
    private static final int LOCAL_OPS_PER_CHECKPOINT = 25;

    /** Fixed tables (2 databases × 2 tables). */
    private static final String[] TABLE_KEYS = {
            "shop.users",
            "shop.orders",
            "inventory.items",
            "inventory.bins"
    };

    private MixedCrashHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        String mode = args[0].trim().toLowerCase(Locale.ROOT);
        int exit = switch (mode) {
            case "load" -> {
                requireArgs(args, 5);
                int ops = args.length >= 6 ? Integer.parseInt(args[5]) : DEFAULT_OPS;
                yield load(args[1], Integer.parseInt(args[2]), Long.parseLong(args[3]), Path.of(args[4]), ops);
            }
            case "verify" -> {
                requireArgs(args, 5);
                yield verify(args[1], Integer.parseInt(args[2]), Long.parseLong(args[3]), Path.of(args[4]));
            }
            case "checkpoint" -> {
                requireArgs(args, 3);
                yield checkpoint(args[1], Integer.parseInt(args[2]));
            }
            default -> {
                System.err.println("unknown mode: " + mode);
                usage();
                yield 2;
            }
        };
        System.exit(exit);
    }

    private static void usage() {
        System.err.println(
                """
                        usage:
                          MixedCrashHarness load       <host> <port> <seed> <oracleDir> [totalOps]
                          MixedCrashHarness verify     <host> <port> <seed> <oracleDir>
                          MixedCrashHarness checkpoint <host> <port>
                        """
        );
    }

    private static void requireArgs(String[] args, int min) {
        if (args.length < min) {
            usage();
            throw new IllegalArgumentException("need at least " + min + " args");
        }
    }

    /**
     * Seed schema, run concurrent storm, write per-table oracle TSVs, disconnect.
     * Caller force-kills the server afterward so dirty pages stay unflushed.
     */
    static int load(String host, int port, long seed, Path oracleDir, int totalOps) throws Exception {
        Objects.requireNonNull(oracleDir, "oracleDir");
        Files.createDirectories(oracleDir);

        Map<String, ConcurrentHashMap<Integer, String>> oracles = new ConcurrentHashMap<>();
        for (String key : TABLE_KEYS) {
            oracles.put(key, new ConcurrentHashMap<>());
        }
        AtomicInteger nextId = new AtomicInteger(1);
        AtomicInteger opsDone = new AtomicInteger(0);
        AtomicInteger checkpoints = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (DatabaseClient bootstrap = new DatabaseClient(host, port)) {
            seedSchema(bootstrap, oracles, nextId);
        }

        int threads = 1 + WORKER_CLIENTS;
        // Pause every client, DDL runs CHECKPOINT, then resume — no overlapping DML vs ENGINE X.
        CyclicBarrier quietBarrier = new CyclicBarrier(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int clientId = i;
            final boolean ddlRole = clientId == 0;
            workers[i] = new Thread(() -> {
                try (DatabaseClient client = new DatabaseClient(host, port)) {
                    Random rnd = new Random(seed + 31L * clientId);
                    int localOps = 0;
                    ready.countDown();
                    start.await();
                    while (failure.get() == null) {
                        int opIndex = opsDone.incrementAndGet();
                        if (opIndex > totalOps) {
                            // Wake peers blocked on the quiet barrier.
                            quietBarrier.reset();
                            break;
                        }
                        localOps++;
                        try {
                            if (localOps % LOCAL_OPS_PER_CHECKPOINT == 0) {
                                quietBarrier.await(60, java.util.concurrent.TimeUnit.SECONDS);
                                if (ddlRole) {
                                    WireResponse response = client.executeQuery("CHECKPOINT");
                                    if (!hasError(response)) {
                                        checkpoints.incrementAndGet();
                                    }
                                }
                                quietBarrier.await(60, java.util.concurrent.TimeUnit.SECONDS);
                            }
                            if (ddlRole) {
                                runDdlClientOp(client, rnd, oracles, checkpoints);
                            } else {
                                // Workers 1..3 → owner slots 0..2 for UPDATE/DELETE partitioning.
                                runWorkerOp(client, rnd, oracles, nextId, clientId - 1);
                            }
                        } catch (IOException | InterruptedException | java.util.concurrent.BrokenBarrierException
                                 | java.util.concurrent.TimeoutException e) {
                            failure.compareAndSet(null, e);
                            quietBarrier.reset();
                            break;
                        }
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                    ready.countDown();
                }
            }, "mixed-crash-" + clientId);
            workers[i].start();
        }

        ready.await();
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        if (failure.get() != null) {
            failure.get().printStackTrace(System.err);
            return 1;
        }

        // Rebuild oracle from live SELECT so DELETE/UPDATE Ok(0) wire quirks cannot drift.
        try (DatabaseClient reconcile = new DatabaseClient(host, port)) {
            reconcileOracles(reconcile, oracles);
        }

        writeOracles(oracleDir, oracles);
        int committed = oracles.values().stream().mapToInt(Map::size).sum();
        Files.writeString(
                oracleDir.resolve("meta.txt"),
                "seed=" + seed
                        + "\ntotalOps=" + totalOps
                        + "\ncheckpoints≈" + checkpoints.get()
                        + "\ncommittedRows=" + committed
                        + "\n",
                StandardCharsets.UTF_8
        );
        System.out.println(
                "[MixedCrashHarness] load done seed=" + seed
                        + " ops=" + totalOps
                        + " committedRows=" + committed
                        + " oracleDir=" + oracleDir
        );
        return 0;
    }

    /** Replace in-memory oracles with what the server currently returns (pre-crash snapshot). */
    private static void reconcileOracles(
            DatabaseClient client,
            Map<String, ConcurrentHashMap<Integer, String>> oracles
    ) throws IOException {
        for (String table : TABLE_KEYS) {
            WireResponse response = client.executeQuery(selectSql(table));
            if (hasError(response)) {
                throw new IOException("reconcile SELECT failed for " + table);
            }
            ConcurrentHashMap<Integer, String> map = oracles.get(table);
            map.clear();
            for (List<Object> row : normalizeRows(resultRows(response), table)) {
                int id = (Integer) row.get(0);
                if (table.equals("shop.orders")) {
                    map.put(id, row.get(1) + "\t" + row.get(2));
                } else {
                    map.put(id, String.valueOf(row.get(1)));
                }
            }
        }
    }

    /** After kill -9 + restart: each table's SELECT must match its oracle TSV. */
    static int verify(String host, int port, long seed, Path oracleDir) throws IOException {
        try (DatabaseClient client = new DatabaseClient(host, port)) {
            for (String table : TABLE_KEYS) {
                Path file = oracleDir.resolve(table + ".tsv");
                List<String> expectedLines = Files.exists(file)
                        ? Files.readAllLines(file, StandardCharsets.UTF_8)
                        : List.of();
                List<List<Object>> expected = parseOracle(expectedLines);
                String sql = selectSql(table);
                WireResponse response = client.executeQuery(sql);
                if (hasError(response)) {
                    System.err.println("[MixedCrashHarness] SELECT failed for " + table);
                    return 1;
                }
                List<List<Object>> actual = normalizeRows(resultRows(response), table);
                expected.sort(byId());
                actual.sort(byId());
                if (!expected.equals(actual)) {
                    System.err.println(
                            "[MixedCrashHarness] MISMATCH table=" + table
                                    + " seed=" + seed
                                    + " expectedSize=" + expected.size()
                                    + " actualSize=" + actual.size()
                    );
                    System.err.println("expected: " + expected);
                    System.err.println("actual:   " + actual);
                    return 1;
                }
                System.out.println(
                        "[MixedCrashHarness] verify OK " + table + " rows=" + expected.size()
                );
            }
        }
        System.out.println("[MixedCrashHarness] verify OK seed=" + seed);
        return 0;
    }

    static int checkpoint(String host, int port) throws IOException {
        try (DatabaseClient client = new DatabaseClient(host, port)) {
            WireResponse response = client.executeQuery("CHECKPOINT");
            if (hasError(response)) {
                System.err.println("[MixedCrashHarness] CHECKPOINT failed");
                return 1;
            }
        }
        System.out.println("[MixedCrashHarness] CHECKPOINT OK");
        return 0;
    }

    private static void seedSchema(
            DatabaseClient client,
            Map<String, ConcurrentHashMap<Integer, String>> oracles,
            AtomicInteger nextId
    ) throws IOException {
        requireOk(client, "CREATE DATABASE shop");
        requireOk(client, "CREATE DATABASE inventory");
        requireOk(client, "CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)");
        requireOk(client, "CREATE TABLE shop.orders (id INT PRIMARY KEY, user_id INT, amount INT)");
        requireOk(client, "CREATE TABLE inventory.items (id INT PRIMARY KEY, sku VARCHAR)");
        requireOk(client, "CREATE TABLE inventory.bins (id INT PRIMARY KEY, label VARCHAR)");
        // Seed a few rows so UPDATE/DELETE/DQL are not always empty.
        for (int i = 0; i < 8; i++) {
            int id = nextId.getAndIncrement();
            requireOk(client, "INSERT INTO shop.users VALUES (" + id + ", 'seed" + id + "')");
            oracles.get("shop.users").put(id, "seed" + id);
        }
        for (int i = 0; i < 8; i++) {
            int id = nextId.getAndIncrement();
            int userId = 1 + (i % 8);
            requireOk(client, "INSERT INTO shop.orders VALUES (" + id + ", " + userId + ", " + (i * 10) + ")");
            oracles.get("shop.orders").put(id, userId + "\t" + (i * 10));
        }
        for (int i = 0; i < 4; i++) {
            int id = nextId.getAndIncrement();
            requireOk(client, "INSERT INTO inventory.items VALUES (" + id + ", 'sku" + id + "')");
            oracles.get("inventory.items").put(id, "sku" + id);
        }
        for (int i = 0; i < 4; i++) {
            int id = nextId.getAndIncrement();
            requireOk(client, "INSERT INTO inventory.bins VALUES (" + id + ", 'bin" + id + "')");
            oracles.get("inventory.bins").put(id, "bin" + id);
        }
        requireOk(client, "CREATE INDEX idx_users_name ON shop.users (name)");
        requireOk(client, "CREATE INDEX idx_orders_user ON shop.orders (user_id)");
    }

    /**
     * DDL client: ~50% DDL, ~20% opportunistic CHECKPOINT, ~30% DQL.
     * Reliable flushes happen on the {@link CyclicBarrier} quiet pauses.
     */
    private static void runDdlClientOp(
            DatabaseClient client,
            Random rnd,
            Map<String, ConcurrentHashMap<Integer, String>> oracles,
            AtomicInteger checkpoints
    ) throws IOException {
        int roll = rnd.nextInt(100);
        if (roll < 50) {
            runDdl(client, rnd);
        } else if (roll < 70) {
            WireResponse response = client.executeQuery("CHECKPOINT");
            if (!hasError(response)) {
                checkpoints.incrementAndGet();
            }
        } else {
            runDql(client, rnd, oracles);
        }
    }

    /**
     * Worker: ~88% DML / ~12% DQL. UPDATE/DELETE only touch ids owned by this worker
     * ({@code id % WORKER_CLIENTS == ownerSlot}) so oracles stay coherent under concurrency.
     */
    private static void runWorkerOp(
            DatabaseClient client,
            Random rnd,
            Map<String, ConcurrentHashMap<Integer, String>> oracles,
            AtomicInteger nextId,
            int ownerSlot
    ) throws IOException {
        int roll = rnd.nextInt(100);
        if (roll < 88) {
            runDml(client, rnd, oracles, nextId, ownerSlot);
        } else {
            runDql(client, rnd, oracles);
        }
    }

    private static void runDdl(DatabaseClient client, Random rnd) throws IOException {
        // Index-only DDL for v1: ADD COLUMN changes INSERT arity and confuses workers.
        int pick = rnd.nextInt(4);
        String sql = switch (pick) {
            case 0 -> "CREATE INDEX idx_users_name ON shop.users (name)";
            case 1 -> "DROP INDEX idx_users_name";
            case 2 -> "CREATE INDEX idx_items_sku ON inventory.items (sku)";
            default -> "DROP INDEX idx_items_sku";
        };
        // already-exists / missing index — ignore; oracle is row-based.
        client.executeQuery(sql);
    }

    private static void runDml(
            DatabaseClient client,
            Random rnd,
            Map<String, ConcurrentHashMap<Integer, String>> oracles,
            AtomicInteger nextId,
            int ownerSlot
    ) throws IOException {
        int tablePick = rnd.nextInt(4);
        int kind = rnd.nextInt(100);
        if (tablePick == 0) {
            ConcurrentHashMap<Integer, String> o = oracles.get("shop.users");
            if (kind < 50) {
                int id = nextId.getAndIncrement();
                String name = "u" + id;
                if (ok(client, "INSERT INTO shop.users VALUES (" + id + ", '" + name + "')")) {
                    o.put(id, name);
                }
            } else if (kind < 80) {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null) {
                    String name = "x" + id + "_" + rnd.nextInt(1000);
                    if (ok(client, "UPDATE shop.users SET name = '" + name + "' WHERE id = " + id)) {
                        o.put(id, name);
                    }
                }
            } else {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null
                        && ok(client, "DELETE FROM shop.users WHERE id = " + id)) {
                    o.remove(id);
                }
            }
            return;
        }
        if (tablePick == 1) {
            ConcurrentHashMap<Integer, String> o = oracles.get("shop.orders");
            if (kind < 50) {
                int id = nextId.getAndIncrement();
                int userId = 1 + rnd.nextInt(Math.max(1, nextId.get()));
                int amount = rnd.nextInt(1000);
                if (ok(client, "INSERT INTO shop.orders VALUES (" + id + ", " + userId + ", " + amount + ")")) {
                    o.put(id, userId + "\t" + amount);
                }
            } else if (kind < 80) {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null) {
                    int amount = rnd.nextInt(1000);
                    String prev = o.get(id);
                    if (prev != null) {
                        String userId = prev.split("\t", 2)[0];
                        if (ok(client, "UPDATE shop.orders SET amount = " + amount + " WHERE id = " + id)) {
                            o.put(id, userId + "\t" + amount);
                        }
                    }
                }
            } else {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null
                        && ok(client, "DELETE FROM shop.orders WHERE id = " + id)) {
                    o.remove(id);
                }
            }
            return;
        }
        if (tablePick == 2) {
            ConcurrentHashMap<Integer, String> o = oracles.get("inventory.items");
            if (kind < 55) {
                int id = nextId.getAndIncrement();
                String sku = "sku" + id;
                if (ok(client, "INSERT INTO inventory.items VALUES (" + id + ", '" + sku + "')")) {
                    o.put(id, sku);
                }
            } else if (kind < 85) {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null) {
                    String sku = "skuX" + id;
                    if (ok(client, "UPDATE inventory.items SET sku = '" + sku + "' WHERE id = " + id)) {
                        o.put(id, sku);
                    }
                }
            } else {
                Integer id = randomOwnedKey(o, rnd, ownerSlot);
                if (id != null
                        && ok(client, "DELETE FROM inventory.items WHERE id = " + id)) {
                    o.remove(id);
                }
            }
            return;
        }
        ConcurrentHashMap<Integer, String> o = oracles.get("inventory.bins");
        if (kind < 55) {
            int id = nextId.getAndIncrement();
            String label = "bin" + id;
            if (ok(client, "INSERT INTO inventory.bins VALUES (" + id + ", '" + label + "')")) {
                o.put(id, label);
            }
        } else if (kind < 85) {
            Integer id = randomOwnedKey(o, rnd, ownerSlot);
            if (id != null) {
                String label = "binX" + id;
                if (ok(client, "UPDATE inventory.bins SET label = '" + label + "' WHERE id = " + id)) {
                    o.put(id, label);
                }
            }
        } else {
            Integer id = randomOwnedKey(o, rnd, ownerSlot);
            if (id != null
                    && ok(client, "DELETE FROM inventory.bins WHERE id = " + id)) {
                o.remove(id);
            }
        }
    }

    private static void runDql(
            DatabaseClient client,
            Random rnd,
            Map<String, ConcurrentHashMap<Integer, String>> oracles
    ) throws IOException {
        int pick = rnd.nextInt(6);
        String sql = switch (pick) {
            case 0 -> "SELECT id, name FROM shop.users";
            case 1 -> {
                Integer id = randomKey(oracles.get("shop.users"), rnd);
                yield id == null
                        ? "SELECT id, name FROM shop.users"
                        : "SELECT id, name FROM shop.users WHERE id = " + id;
            }
            case 2 -> "SELECT id, user_id, amount FROM shop.orders";
            case 3 -> "SELECT id, sku FROM inventory.items";
            case 4 -> "SHOW TABLES FROM shop";
            default -> "DESCRIBE shop.users";
        };
        client.executeQuery(sql);
    }

    private static Integer randomKey(ConcurrentHashMap<Integer, String> map, Random rnd) {
        if (map.isEmpty()) {
            return null;
        }
        List<Integer> keys = new ArrayList<>(map.keySet());
        return keys.get(rnd.nextInt(keys.size()));
    }

    /** Prefer keys this worker owns so concurrent UPDATE/DELETE do not corrupt the oracle. */
    private static Integer randomOwnedKey(
            ConcurrentHashMap<Integer, String> map,
            Random rnd,
            int ownerSlot
    ) {
        List<Integer> owned = new ArrayList<>();
        for (Integer id : map.keySet()) {
            if (Math.floorMod(id, WORKER_CLIENTS) == ownerSlot) {
                owned.add(id);
            }
        }
        if (owned.isEmpty()) {
            return null;
        }
        return owned.get(rnd.nextInt(owned.size()));
    }

    private static void writeOracles(
            Path oracleDir,
            Map<String, ConcurrentHashMap<Integer, String>> oracles
    ) throws IOException {
        for (String table : TABLE_KEYS) {
            ConcurrentHashMap<Integer, String> map = oracles.get(table);
            List<Integer> ids = new ArrayList<>(map.keySet());
            ids.sort(Integer::compareTo);
            List<String> lines = new ArrayList<>();
            for (Integer id : ids) {
                lines.add(id + "\t" + map.get(id));
            }
            Files.write(oracleDir.resolve(table + ".tsv"), lines, StandardCharsets.UTF_8);
        }
    }

    private static List<List<Object>> parseOracle(List<String> lines) {
        List<List<Object>> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length == 2) {
                rows.add(List.of(Integer.valueOf(parts[0]), parts[1]));
            } else if (parts.length == 3) {
                rows.add(List.of(
                        Integer.valueOf(parts[0]),
                        Integer.valueOf(parts[1]),
                        Integer.valueOf(parts[2])
                ));
            }
        }
        return rows;
    }

    private static String selectSql(String table) {
        return switch (table) {
            case "shop.users" -> "SELECT id, name FROM shop.users";
            case "shop.orders" -> "SELECT id, user_id, amount FROM shop.orders";
            case "inventory.items" -> "SELECT id, sku FROM inventory.items";
            case "inventory.bins" -> "SELECT id, label FROM inventory.bins";
            default -> throw new IllegalArgumentException(table);
        };
    }

    private static List<List<Object>> normalizeRows(List<List<Object>> rows, String table) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            if (table.equals("shop.orders")) {
                out.add(List.of(
                        ((Number) row.get(0)).intValue(),
                        ((Number) row.get(1)).intValue(),
                        ((Number) row.get(2)).intValue()
                ));
            } else {
                out.add(List.of(((Number) row.get(0)).intValue(), String.valueOf(row.get(1))));
            }
        }
        return out;
    }

    private static Comparator<List<Object>> byId() {
        return Comparator.comparingInt(row -> (Integer) row.get(0));
    }

    private static void requireOk(DatabaseClient client, String sql) throws IOException {
        if (!ok(client, sql)) {
            throw new IOException("required SQL failed: " + sql);
        }
    }

    /** True when the response is not ERROR (0-row UPDATE/DELETE still count as OK). */
    private static boolean ok(DatabaseClient client, String sql) throws IOException {
        return !hasError(client.executeQuery(sql));
    }


    private static boolean hasError(WireResponse response) {
        return response.messages().stream().anyMatch(WireMessage.Error.class::isInstance);
    }

    private static List<List<Object>> resultRows(WireResponse response) {
        return response.messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no result set"))
                .rows();
    }
}
