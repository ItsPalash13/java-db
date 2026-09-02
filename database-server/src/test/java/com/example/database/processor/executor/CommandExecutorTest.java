package com.example.database.processor.executor;

import com.example.database.processor.planner.AddColumnPlan;
import com.example.database.processor.planner.CreateDatabasePlan;
import com.example.database.processor.planner.CreateIndexPlan;
import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.DropColumnPlan;
import com.example.database.processor.planner.DropDatabasePlan;
import com.example.database.processor.planner.DropIndexPlan;
import com.example.database.processor.planner.DropTablePlan;
import com.example.database.processor.planner.UnresolvedPlan;
import com.example.database.processor.analyser.UnresolvedQuery;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.transaction.DefaultTransactionManager;
import com.example.database.storage.undo.DefaultUndoManager;
import com.example.database.storage.wal.DefaultWALManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void createTableWritesCatalogWithoutIdsOnThePlan() {
        CatalogManager catalog = catalogWithShop();
        CommandExecutor executor = newExecutor(catalog);
        List<ColumnMetadata> columns = List.of(
                ColumnMetadata.define("id", ColumnType.INT),
                ColumnMetadata.define("name", ColumnType.VARCHAR)
        );

        QueryResult result = executor.execute(new CreateTablePlan("shop", "users", columns));

        assertEquals("OK", result.toResponse());
        TableMetadata created = catalog.getTable("shop", "users").orElseThrow();
        assertEquals(1, created.tableId().orElseThrow());
        assertEquals("id", created.columns().get(0).name());
        assertEquals(ColumnType.INT, created.columns().get(0).type());
        assertEquals("name", created.columns().get(1).name());
        assertEquals(ColumnType.VARCHAR, created.columns().get(1).type());
        assertTrue(created.columns().get(0).columnId().isPresent());
    }

    @Test
    void createTableConflictBecomesExecutionError() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        CommandExecutor executor = newExecutor(catalog);

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> executor.execute(new CreateTablePlan(
                        "shop",
                        "users",
                        List.of(ColumnMetadata.define("id", ColumnType.INT))
                ))
        );
        assertEquals("table already exists: shop.users", ex.getMessage());
        assertEquals("ERROR: table already exists: shop.users", ex.toResponse());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void dropTableRemovesCatalogEntry() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        CommandExecutor executor = newExecutor(catalog);

        assertEquals("OK", executor.execute(new DropTablePlan("shop", "users")).toResponse());
        assertFalse(catalog.tableExists("shop", "users"));
    }

    @Test
    void addColumnAppendsColumnWithNextId() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        CommandExecutor executor = newExecutor(catalog);

        assertEquals(
                "OK",
                executor.execute(new AddColumnPlan(
                        "shop",
                        "users",
                        ColumnMetadata.define("age", ColumnType.INT)
                )).toResponse()
        );

        TableMetadata users = catalog.getTable("shop", "users").orElseThrow();
        assertEquals(3, users.columns().size());
        ColumnMetadata age = users.columns().get(2);
        assertEquals("age", age.name());
        assertEquals(ColumnType.INT, age.type());
        assertEquals(3, age.columnId().orElseThrow());
        assertTrue(age.nullable());
    }

    @Test
    void dropColumnRemovesColumnFromCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        CommandExecutor executor = newExecutor(catalog);

        assertEquals(
                "OK",
                executor.execute(new DropColumnPlan("shop", "users", "name")).toResponse()
        );

        TableMetadata users = catalog.getTable("shop", "users").orElseThrow();
        assertEquals(1, users.columns().size());
        assertEquals("id", users.columns().get(0).name());
        assertEquals(1, users.columns().get(0).columnId().orElseThrow());
    }

    @Test
    void createIndexAndDropIndexUpdateCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        CommandExecutor executor = newExecutor(catalog);

        assertEquals(
                "OK",
                executor.execute(new CreateIndexPlan("shop", "users", "idx_users_id", List.of(1))).toResponse()
        );
        TableMetadata users = catalog.getTable("shop", "users").orElseThrow();
        assertEquals(1, users.indexes().size());
        assertEquals("idx_users_id", users.indexes().get(0).name());

        assertEquals(
                "OK",
                executor.execute(new DropIndexPlan("shop", "users", "idx_users_id")).toResponse()
        );
        assertTrue(catalog.getTable("shop", "users").orElseThrow().indexes().isEmpty());
    }

    @Test
    void rejectsUnresolvedPlans() {
        CatalogManager catalog = new DefaultCatalogManager();
        CommandExecutor executor = newExecutor(catalog);
        UnresolvedPlan plan = new UnresolvedPlan(
                new UnresolvedQuery(new SelectQuery(true, List.of(), new QualifiedTable("shop", "users"), null))
        );

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> executor.execute(plan)
        );
        assertTrue(ex.getMessage().contains("UNRESOLVED"));
        assertTrue(catalog.allTables().isEmpty());
    }

    @Test
    void createAndDropDatabaseUpdateCatalog() {
        CatalogManager catalog = new DefaultCatalogManager();
        CommandExecutor executor = newExecutor(catalog);

        assertEquals("OK", executor.execute(new CreateDatabasePlan("shop")).toResponse());
        assertTrue(catalog.databaseExists("shop"));

        assertEquals("OK", executor.execute(new DropDatabasePlan("shop")).toResponse());
        assertTrue(catalog.allDatabases().isEmpty());
    }

    private static CatalogManager catalogWithShop() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        return catalog;
    }

    private CommandExecutor newExecutor(CatalogManager catalog) {
        WALManager wal = new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)));
        return new CommandExecutor(
                catalog,
                new DefaultTransactionManager(wal, new DefaultUndoManager()),
                new DefaultLockManager(),
                wal,
                new com.example.database.storage.table.InMemoryTableStore()
        );
    }
}
