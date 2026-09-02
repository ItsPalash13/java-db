package com.example.database.processor.executor;

import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.transaction.DefaultTransactionManager;
import com.example.database.storage.undo.DefaultUndoManager;
import com.example.database.storage.wal.DefaultWALManager;
import com.example.database.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two threads racing CREATE TABLE shop.users — catalog lock ensures one OK and one conflict,
 * and a single tableId assignment.
 */
class CommandExecutorConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentCreateTableSameNameOneWins() throws Exception {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        LockManager locks = new DefaultLockManager();
        WALManager wal = new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)));
        CommandExecutor executor = new CommandExecutor(
                catalog,
                new DefaultTransactionManager(wal, new DefaultUndoManager()),
                locks,
                wal,
                new com.example.database.storage.table.InMemoryTableStore()
        );

        CreateTablePlan plan = new CreateTablePlan(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        );

        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    QueryResult result = executor.execute(plan);
                    if ("OK".equals(result.toResponse())) {
                        okCount.incrementAndGet();
                    }
                } catch (ExecutionException e) {
                    errorCount.incrementAndGet();
                    assertTrue(e.getMessage().contains("already exists"));
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, okCount.get());
        assertEquals(1, errorCount.get());
        assertEquals(1, catalog.allTables().size());
        TableMetadata users = catalog.getTable("shop", "users").orElseThrow();
        assertEquals(1, users.tableId().orElseThrow());
    }
}
