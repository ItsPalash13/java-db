package com.example.client;

import com.example.client.wire.ResponsePrinter;
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
import java.util.Random;

/**
 * Process-level WAL crash helper used by {@code scripts/wal_crash_verify.sh}.
 * <p>
 * {@code load}: insert / CHECKPOINT / ROLLBACK churn; write committed rows to
 * {@code expectedPath}; leave the server process running (dirty pages unflushed).
 * {@code verify}: after force-kill + restart, {@code SELECT *} must equal that file.
 *
 * <pre>
 * java ... WalCrashHarness load  127.0.0.1 9090 42 out/wal-crash/expected.jsonl
 * java ... WalCrashHarness verify 127.0.0.1 9090 42 out/wal-crash/expected.jsonl
 * </pre>
 */
public final class WalCrashHarness {

    private static final int STEPS = 80;

    private WalCrashHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println(
                    "usage: WalCrashHarness <load|verify> <host> <port> <seed> <expectedPath>"
            );
            System.exit(2);
        }
        String mode = args[0].trim().toLowerCase(Locale.ROOT);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        long seed = Long.parseLong(args[3]);
        Path expectedPath = Path.of(args[4]);
        int exit = switch (mode) {
            case "load" -> load(host, port, seed, expectedPath);
            case "verify" -> verify(host, port, seed, expectedPath);
            default -> {
                System.err.println("unknown mode: " + mode);
                yield 2;
            }
        };
        System.exit(exit);
    }

    /**
     * Churn until a seeded kill step, persist the committed oracle, disconnect.
     * Caller must force-kill the server without a clean stop so dirty pages stay RAM-only.
     */
    static int load(String host, int port, long seed, Path expectedPath) throws IOException {
        Random rnd = new Random(seed);
        int killAt = 25 + rnd.nextInt(STEPS - 30);
        List<String> expectedLines = new ArrayList<>();
        int nextId = 1;

        try (DatabaseClient client = new DatabaseClient(host, port)) {
            requireOk(client, "CREATE DATABASE shop");
            requireOk(client, "CREATE TABLE shop.users (id INT, name VARCHAR)");

            for (int step = 0; step < killAt; step++) {
                int roll = rnd.nextInt(100);
                if (roll < 8) {
                    requireOk(client, "BEGIN");
                    requireOk(client, "INSERT INTO shop.users VALUES (" + nextId + ", 'ghost" + nextId + "')");
                    requireOk(client, "ROLLBACK");
                    nextId++;
                } else if (roll < 20) {
                    requireOk(client, "CHECKPOINT");
                } else {
                    String name = "user" + nextId;
                    requireOk(client, "INSERT INTO shop.users VALUES (" + nextId + ", '" + name + "')");
                    expectedLines.add(nextId + "\t" + name);
                    nextId++;
                }
            }
        }

        Path parent = expectedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(expectedPath, expectedLines, StandardCharsets.UTF_8);
        Files.writeString(
                expectedPath.resolveSibling("meta.txt"),
                "seed=" + seed + "\nkillAt=" + killAt + "\ncommitted=" + expectedLines.size() + "\n",
                StandardCharsets.UTF_8
        );
        System.out.println(
                "[WalCrashHarness] load done seed=" + seed
                        + " killAt=" + killAt
                        + " committed=" + expectedLines.size()
                        + " expected=" + expectedPath
        );
        return 0;
    }

    /** After recovery, SELECT rows must match the load-phase oracle file. */
    static int verify(String host, int port, long seed, Path expectedPath) throws IOException {
        List<String> expectedLines = Files.readAllLines(expectedPath, StandardCharsets.UTF_8);
        List<List<Object>> expected = new ArrayList<>();
        for (String line : expectedLines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            expected.add(List.of(Integer.valueOf(parts[0]), parts[1]));
        }
        expected.sort(Comparator.comparingInt(row -> (Integer) row.get(0)));

        try (DatabaseClient client = new DatabaseClient(host, port)) {
            WireResponse response = client.executeQuery("SELECT id, name FROM shop.users");
            if (hasError(response)) {
                System.err.println("[WalCrashHarness] SELECT error after recovery");
                printResponse(response);
                return 1;
            }
            List<List<Object>> actual = resultRows(response);
            actual.sort(Comparator.comparingInt(row -> ((Number) row.get(0)).intValue()));
            // Wire JSON may decode ints as Long — normalize for compare.
            List<List<Object>> normalized = new ArrayList<>();
            for (List<Object> row : actual) {
                normalized.add(List.of(((Number) row.get(0)).intValue(), String.valueOf(row.get(1))));
            }
            if (!expected.equals(normalized)) {
                System.err.println(
                        "[WalCrashHarness] MISMATCH seed=" + seed
                                + " expectedSize=" + expected.size()
                                + " actualSize=" + normalized.size()
                );
                System.err.println("expected: " + expected);
                System.err.println("actual:   " + normalized);
                return 1;
            }
        }
        System.out.println(
                "[WalCrashHarness] verify OK seed=" + seed + " rows=" + expected.size()
        );
        return 0;
    }

    private static void requireOk(DatabaseClient client, String sql) throws IOException {
        WireResponse response = client.executeQuery(sql);
        if (hasError(response)) {
            throw new IOException("SQL failed: " + sql + " -> " + response);
        }
    }

    private static boolean hasError(WireResponse response) {
        return response.messages().stream().anyMatch(WireMessage.Error.class::isInstance);
    }

    private static void printResponse(WireResponse response) {
        new ResponsePrinter(System.err).print(response);
    }

    private static List<List<Object>> resultRows(WireResponse response) {
        return response.messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no result set in response"))
                .rows();
    }
}
