package com.example.database.processor.executor;

import com.example.database.processor.DefaultQueryProcessor;
import com.example.database.processor.planner.BeginPlan;
import com.example.database.processor.planner.CommitPlan;
import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.RollbackPlan;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.transaction.DefaultTransactionManager;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.DefaultWALManager;
import com.example.database.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitTransactionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void beginCreateTwoTablesCommitPersistsBoth() {
        ExplicitTxnFixture fixture = newFixture();
        fixture.transactionControl().execute(new BeginPlan());
        fixture.executor().execute(new CreateTablePlan("shop", "a", List.of(
                ColumnMetadata.define("id", ColumnType.INT)
        )));
        fixture.executor().execute(new CreateTablePlan("shop", "b", List.of(
                ColumnMetadata.define("id", ColumnType.INT)
        )));
        assertEquals("OK", fixture.transactionControl().execute(new CommitPlan()).toResponse());

        assertTrue(fixture.catalog().tableExists("shop", "a"));
        assertTrue(fixture.catalog().tableExists("shop", "b"));
    }

    @Test
    void beginCreateTwoTablesRollbackPersistsNeither() {
        ExplicitTxnFixture fixture = newFixture();
        fixture.transactionControl().execute(new BeginPlan());
        fixture.executor().execute(new CreateTablePlan("shop", "a", List.of(
                ColumnMetadata.define("id", ColumnType.INT)
        )));
        fixture.executor().execute(new CreateTablePlan("shop", "b", List.of(
                ColumnMetadata.define("id", ColumnType.INT)
        )));
        assertEquals("OK", fixture.transactionControl().execute(new RollbackPlan()).toResponse());

        assertFalse(fixture.catalog().tableExists("shop", "a"));
        assertFalse(fixture.catalog().tableExists("shop", "b"));
    }

    @Test
    void secondBeginWhileInExplicitTransactionFails() {
        ExplicitTxnFixture fixture = newFixture();
        fixture.transactionControl().execute(new BeginPlan());

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> fixture.transactionControl().execute(new BeginPlan())
        );
        assertTrue(ex.getMessage().contains("already active"));
        fixture.transactionControl().execute(new RollbackPlan());
    }

    @Test
    void queryProcessorEndToEndMultiStatementCommit() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
        engine.catalogManager().createDatabase("shop");

        assertEquals("OK", processor.executeText("BEGIN"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.a (id INT)"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.b (id INT)"));
        assertEquals("OK", processor.executeText("COMMIT"));

        assertTrue(engine.catalogManager().tableExists("shop", "a"));
        assertTrue(engine.catalogManager().tableExists("shop", "b"));
        engine.stop();
    }

    @Test
    void queryProcessorRollbackDiscardsExplicitWork() {
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
        engine.catalogManager().createDatabase("shop");

        assertEquals("OK", processor.executeText("BEGIN"));
        assertEquals("OK", processor.executeText("CREATE TABLE shop.a (id INT)"));
        assertEquals("OK", processor.executeText("ROLLBACK"));

        assertFalse(engine.catalogManager().tableExists("shop", "a"));
        engine.stop();
    }

    private ExplicitTxnFixture newFixture() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        WALManager wal = new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)));
        TransactionManager tx = new DefaultTransactionManager(wal);
        LockManager lock = new DefaultLockManager();
        CommandExecutor ddl = new CommandExecutor(
                catalog,
                tx,
                lock,
                wal,
                new com.example.database.storage.table.InMemoryTableStore()
        );
        TransactionControlExecutor control = new TransactionControlExecutor(tx, lock, catalog);
        return new ExplicitTxnFixture(catalog, ddl, control);
    }

    private record ExplicitTxnFixture(
            CatalogManager catalog,
            CommandExecutor executor,
            TransactionControlExecutor transactionControl
    ) {
    }
}
