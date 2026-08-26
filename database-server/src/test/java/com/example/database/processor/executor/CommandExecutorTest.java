package com.example.database.processor.executor;

import com.example.database.processor.planner.CreateDatabasePlan;
import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.DropDatabasePlan;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutorTest {

    @Test
    void createTableWritesCatalogWithoutIdsOnThePlan() {
        CatalogManager catalog = catalogWithShop();
        CommandExecutor executor = new CommandExecutor(catalog);
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
        CommandExecutor executor = new CommandExecutor(catalog);

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
        CommandExecutor executor = new CommandExecutor(catalog);

        assertEquals("OK", executor.execute(new DropTablePlan("shop", "users")).toResponse());
        assertFalse(catalog.tableExists("shop", "users"));
    }

    @Test
    void rejectsUnresolvedPlans() {
        CatalogManager catalog = new DefaultCatalogManager();
        CommandExecutor executor = new CommandExecutor(catalog);
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
        CommandExecutor executor = new CommandExecutor(catalog);

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
}
