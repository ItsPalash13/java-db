package com.example.database.processor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.analyser.DefaultQueryAnalyser;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.lexer.DefaultQueryLexer;
import com.example.database.processor.parser.DefaultQueryParser;
import com.example.database.processor.planner.AccessPath;
import com.example.database.processor.planner.DefaultQueryPlanner;
import com.example.database.processor.planner.SelectPlan;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress: 1k mixed-type inserts in shuffled id order (B+ tree non-sequential keys),
 * bulk {@code CREATE INDEX}, then mixed SELECT shapes. Heap scans expect insert order;
 * index equality probes expect single matching rows. Overwrites {@code logs/insert-1k-stress.log}.
 */
class Insert1kIndexStressTest {

    private static final int ROW_COUNT = 1000;
    private static final int EXTRA_AFTER_INDEX = 10;
    /** Fixed seed so failures are reproducible across runs. */
    private static final long SHUFFLE_SEED = 42L;

    @TempDir
    Path dataDir;

    @Test
    void insert1kThenMixedSelects() throws Exception {
        Path logFile = stressLogFile();
        Files.createDirectories(logFile.getParent());
        Path root = dataDir.resolve("store");
        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try (BufferedWriter log = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            DefaultQueryProcessor client = new DefaultQueryProcessor(engine);
            logLine(log, "Insert1kIndexStressTest started at " + LocalDateTime.now());
            logLine(log, "log file: " + logFile.toAbsolutePath());

            runAndLog(client, log, "CREATE DATABASE shop");
            runAndLog(client, log, "CREATE TABLE shop.items (id INT, name VARCHAR, active BOOLEAN)");

            // Heap order = insert order; shuffle keys so index build is not monotonic.
            List<Integer> insertOrder = shuffledIds(1, ROW_COUNT, SHUFFLE_SEED);
            logLine(log, "shuffle seed=" + SHUFFLE_SEED + " insertOrder[0..9]=" + insertOrder.subList(0, 10));
            for (int id : insertOrder) {
                runAndLog(client, log, insertSql(id));
            }

            runAndLog(client, log, "CREATE INDEX idx_items_id ON shop.items (id)");
            Path idxFile = root.resolve("shop").resolve("items").resolve("idx_items_id.idx");
            assertTrue(Files.isRegularFile(idxFile), "expected physical index file " + idxFile);
            logLine(log, "index file: " + idxFile);

            int last = ROW_COUNT;
            for (int i = ROW_COUNT + 1; i <= ROW_COUNT + EXTRA_AFTER_INDEX; i++) {
                runAndLog(client, log, insertSql(i));
                insertOrder.add(i);
                last = i;
            }

            logLine(log, "--- mixed SELECTs (last id=" + last + ") ---");

            // Full heap scan: no WHERE, all columns (order = insertOrder).
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items",
                    AccessPath.Kind.TABLE_SCAN,
                    expectedStar(insertOrder, id -> true)
            );
            // Projection-only table scan.
            assertSelect(
                    engine, client, log,
                    "SELECT name FROM shop.items",
                    AccessPath.Kind.TABLE_SCAN,
                    expectedNames(insertOrder, id -> true)
            );
            // Range predicates on the indexed leading column use INDEX_SCAN (index key order).
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id > 990",
                    AccessPath.Kind.INDEX_SCAN,
                    expectedStarSorted(last, id -> id > 990)
            );
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id < 5",
                    AccessPath.Kind.INDEX_SCAN,
                    expectedStarSorted(last, id -> id < 5)
            );
            assertSelect(
                    engine, client, log,
                    "SELECT id, name FROM shop.items WHERE id >= 1005",
                    AccessPath.Kind.INDEX_SCAN,
                    expectedIdNameSorted(last, id -> id >= 1005)
            );
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id <= 3",
                    AccessPath.Kind.INDEX_SCAN,
                    expectedStarSorted(last, id -> id <= 3)
            );
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id != 1",
                    AccessPath.Kind.TABLE_SCAN,
                    expectedStar(insertOrder, id -> id != 1)
            );
            // Non-leading-index predicates stay on the heap.
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE active = TRUE",
                    AccessPath.Kind.TABLE_SCAN,
                    expectedStar(insertOrder, id -> id % 2 == 0)
            );
            assertSelect(
                    engine, client, log,
                    "SELECT id FROM shop.items WHERE name = 'item42'",
                    AccessPath.Kind.TABLE_SCAN,
                    List.of(List.of(42))
            );
            // Equality on the indexed leading column is the only INDEX_SCAN path.
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id = 7",
                    AccessPath.Kind.INDEX_SCAN,
                    List.of(starRow(7))
            );
            assertSelect(
                    engine, client, log,
                    "SELECT name, active FROM shop.items WHERE id = 1000",
                    AccessPath.Kind.INDEX_SCAN,
                    List.of(List.of("item1000", true))
            );
            assertSelect(
                    engine, client, log,
                    "SELECT * FROM shop.items WHERE id = " + last,
                    AccessPath.Kind.INDEX_SCAN,
                    List.of(starRow(last))
            );

            logLine(log, "done: mixed SELECTs over " + last + " rows with idx_items_id");
            System.out.println("[Insert1kStress] wrote client log (overwritten) to " + logFile.toAbsolutePath());
        } finally {
            engine.stop();
        }
    }

    private static List<Integer> shuffledIds(int fromInclusive, int toInclusive, long seed) {
        List<Integer> ids = new ArrayList<>(toInclusive - fromInclusive + 1);
        for (int id = fromInclusive; id <= toInclusive; id++) {
            ids.add(id);
        }
        Collections.shuffle(ids, new Random(seed));
        return ids;
    }

    private static void runAndLog(DefaultQueryProcessor client, BufferedWriter log, String sql) throws IOException {
        String result = client.executeText(sql);
        logLine(log, sql + " -> " + result);
        assertEquals("OK", result, "unexpected client result for: " + sql);
    }

    private static void assertSelect(
            StorageEngine engine,
            DefaultQueryProcessor client,
            BufferedWriter log,
            String sql,
            AccessPath.Kind expectedPath,
            List<List<Object>> expectedRows
    ) throws IOException {
        SelectPlan plan = planSelect(engine, sql);
        assertEquals(expectedPath, plan.accessPath().kind(), "access path for: " + sql);
        if (expectedPath == AccessPath.Kind.INDEX_SCAN) {
            assertEquals("idx_items_id", plan.accessPath().indexName());
        }
        QueryResult select = client.execute(sql);
        List<List<Object>> rows = resultSetRows(select);
        logLine(log, sql + " [" + plan.accessPath() + "] rowCount=" + rows.size());
        for (List<Object> row : rows) {
            logLine(log, "  " + row);
        }
        assertEquals(expectedRows, rows, "row mismatch for: " + sql);
    }

    /** Index range scans return rows in sorted key order, not heap insert order. */
    private static List<List<Object>> expectedStarSorted(int maxId, IntPredicate idMatch) {
        List<List<Object>> rows = new ArrayList<>();
        for (int id = 1; id <= maxId; id++) {
            if (idMatch.test(id)) {
                rows.add(starRow(id));
            }
        }
        return rows;
    }

    private static List<List<Object>> expectedIdNameSorted(int maxId, IntPredicate idMatch) {
        List<List<Object>> rows = new ArrayList<>();
        for (int id = 1; id <= maxId; id++) {
            if (idMatch.test(id)) {
                rows.add(List.of(id, "item" + id));
            }
        }
        return rows;
    }

    /** Heap / SeqScan order follows {@code insertOrder}, not sorted id. */
    private static List<List<Object>> expectedStar(List<Integer> insertOrder, IntPredicate idMatch) {
        List<List<Object>> rows = new ArrayList<>();
        for (int id : insertOrder) {
            if (idMatch.test(id)) {
                rows.add(starRow(id));
            }
        }
        return rows;
    }

    private static List<List<Object>> expectedIdName(List<Integer> insertOrder, IntPredicate idMatch) {
        List<List<Object>> rows = new ArrayList<>();
        for (int id : insertOrder) {
            if (idMatch.test(id)) {
                rows.add(List.of(id, "item" + id));
            }
        }
        return rows;
    }

    private static List<List<Object>> expectedNames(List<Integer> insertOrder, IntPredicate idMatch) {
        List<List<Object>> rows = new ArrayList<>();
        for (int id : insertOrder) {
            if (idMatch.test(id)) {
                rows.add(List.of("item" + id));
            }
        }
        return rows;
    }

    private static List<Object> starRow(int id) {
        return List.of(id, "item" + id, id % 2 == 0);
    }

    private static String insertSql(int id) {
        boolean active = id % 2 == 0;
        return "INSERT INTO shop.items VALUES ("
                + id + ", 'item" + id + "', " + (active ? "TRUE" : "FALSE") + ")";
    }

    /**
     * Repo-root {@code logs/insert-1k-stress.log}. Truncated at the start of each run.
     * {@code *.log} is gitignored.
     */
    private static Path stressLogFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("database-server"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            dir = Path.of("").toAbsolutePath();
        }
        return dir.resolve("logs").resolve("insert-1k-stress.log");
    }

    private static void logLine(BufferedWriter log, String line) throws IOException {
        log.write(line);
        log.newLine();
    }

    private static SelectPlan planSelect(StorageEngine engine, String sql) {
        var tokens = new DefaultQueryLexer().tokenize(sql);
        var ast = new DefaultQueryParser().parse(tokens);
        var analyzed = new DefaultQueryAnalyser(engine.catalogManager()).analyse(ast);
        var plan = new DefaultQueryPlanner().plan(analyzed);
        return assertInstanceOf(SelectPlan.class, plan);
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
