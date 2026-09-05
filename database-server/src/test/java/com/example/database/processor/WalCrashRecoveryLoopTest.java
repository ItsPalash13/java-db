package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.executor.QueryResult;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Seeded insert / CHECKPOINT / occasional ROLLBACK churn, then a crash that skips
 * {@link StorageEngine#stop()} (no {@code flushAll}). Restart must make
 * {@code SELECT *} match only statements that returned OK before the kill.
 * <p>
 * Why not delete {@code .ibd} after stop? CHECKPOINT advances the WAL redo fence and
 * flushes heap pages — wiping the heap would drop pre-fence rows that redo will not
 * re-apply. Abandoning dirty frames matches kill -9: disk keeps flushed pages; WAL
 * covers the unflushed committed tail.
 */
class WalCrashRecoveryLoopTest {

    private static final int STEPS = 80;

    @TempDir
    Path dataDir;

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 42L, 99L, 12345L})
    void seededChurnThenCrashWithoutFlushRecoversExpected(long seed) {
        runCrashLoop(seed, dataDir.resolve("seed-" + seed));
    }

    @Test
    void defaultSeedCrashLoop() {
        runCrashLoop(42L, dataDir.resolve("default"));
    }

    private static void runCrashLoop(long seed, Path root) {
        Random rnd = new Random(seed);
        // Kill after enough work that checkpoints and rollbacks are likely, before the end.
        int killAt = 25 + rnd.nextInt(STEPS - 30);

        List<List<Object>> expected = new ArrayList<>();
        int nextId = 1;

        StorageEngine first = new DefaultStorageEngine(new DataDirectory(root));
        first.start();
        DefaultQueryProcessor processor = new DefaultQueryProcessor(first);
        assertOk(processor, "CREATE DATABASE shop");
        assertOk(processor, "CREATE TABLE shop.users (id INT, name VARCHAR)");

        for (int step = 0; step < killAt; step++) {
            int roll = rnd.nextInt(100);
            if (roll < 8) {
                // Explicit txn that must not appear after recovery.
                assertOk(processor, "BEGIN");
                assertOk(processor, "INSERT INTO shop.users VALUES (" + nextId + ", 'ghost" + nextId + "')");
                assertOk(processor, "ROLLBACK");
                nextId++;
            } else if (roll < 20) {
                assertOk(processor, "CHECKPOINT");
            } else {
                String name = "user" + nextId;
                assertOk(processor, "INSERT INTO shop.users VALUES (" + nextId + ", '" + name + "')");
                expected.add(List.of(nextId, name));
                nextId++;
            }
        }
        // Crash simulation: leave dirty buffer-pool pages unflushed. Do not call stop().
        // Commits already flushed the WAL; CHECKPOINT may have flushed earlier pages.

        StorageEngine second = new DefaultStorageEngine(new DataDirectory(root));
        second.start();
        try {
            DefaultQueryProcessor recovered = new DefaultQueryProcessor(second);
            QueryResult result = recovered.execute("SELECT id, name FROM shop.users");
            assertFalse(result.isError(), () -> "SELECT failed after recovery: " + result);
            List<List<Object>> actual = new ArrayList<>(resultSetRows(result));
            actual.sort(Comparator.comparingInt(row -> (Integer) row.get(0)));
            expected.sort(Comparator.comparingInt(row -> (Integer) row.get(0)));
            assertEquals(
                    expected,
                    actual,
                    () -> "seed=" + seed + " killAt=" + killAt
                            + " expectedSize=" + expected.size()
                            + " actualSize=" + actual.size()
            );
        } finally {
            second.stop();
        }
    }

    private static void assertOk(DefaultQueryProcessor processor, String sql) {
        assertEquals("OK", processor.executeText(sql), () -> "failed: " + sql);
    }

    private static List<List<Object>> resultSetRows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(WireMessage.ResultSet.class::isInstance)
                .map(WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
