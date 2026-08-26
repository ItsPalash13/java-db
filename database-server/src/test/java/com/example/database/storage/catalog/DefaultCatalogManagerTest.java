package com.example.database.storage.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCatalogManagerTest {

    @Test
    void createTableAssignsIdsAndIsLookupable() {
        CatalogManager catalog = catalogWithShop();

        TableMetadata created = catalog.createTable(usersDefinition());

        assertEquals(1, created.tableId().orElseThrow());
        assertEquals("shop", created.database());
        assertEquals("users", created.name());
        assertEquals(2, created.columns().size());

        ColumnMetadata id = created.columns().get(0);
        assertEquals(1, id.columnId().orElseThrow());
        assertEquals("id", id.name());
        assertEquals(ColumnType.INT, id.type());
        assertTrue(id.nullable());

        ColumnMetadata name = created.columns().get(1);
        assertEquals(2, name.columnId().orElseThrow());
        assertEquals("name", name.name());
        assertEquals(ColumnType.VARCHAR, name.type());
        assertTrue(name.nullable());

        assertTrue(catalog.tableExists("shop", "users"));
        assertEquals(created, catalog.getTable("shop", "users").orElseThrow());
    }

    @Test
    void getTableIsEmptyWhenMissing() {
        CatalogManager catalog = catalogWithShop();
        assertTrue(catalog.getTable("shop", "users").isEmpty());
        assertFalse(catalog.tableExists("shop", "users"));
    }

    @Test
    void createTableRejectsMissingDatabase() {
        CatalogManager catalog = new DefaultCatalogManager();
        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(usersDefinition())
        );
        assertEquals("database does not exist: shop", ex.getMessage());
    }

    @Test
    void createTableRejectsDuplicateTableName() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(usersDefinition())
        );
        assertEquals("table already exists: shop.users", ex.getMessage());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void sameTableNameInDifferentDatabasesDoesNotClash() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        catalog.createDatabase("app");
        catalog.createTable(usersDefinition());
        catalog.createTable(TableMetadata.define(
                "app",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertTrue(catalog.tableExists("shop", "users"));
        assertTrue(catalog.tableExists("app", "users"));
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().tableId().orElseThrow());
        assertEquals(2, catalog.getTable("app", "users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void createTableRejectsDuplicateColumnNames() {
        CatalogManager catalog = catalogWithShop();
        TableMetadata duplicateColumns = TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("id", ColumnType.VARCHAR)
                )
        );

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(duplicateColumns)
        );
        assertEquals("duplicate column name: id", ex.getMessage());
        assertFalse(catalog.tableExists("shop", "users"));
    }

    @Test
    void createTableRejectsEmptyColumnList() {
        CatalogManager catalog = catalogWithShop();

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(TableMetadata.define("shop", "users", List.of()))
        );
        assertEquals("table must have at least one column: shop.users", ex.getMessage());
        assertFalse(catalog.tableExists("shop", "users"));
    }

    @Test
    void secondTableGetsNextTableId() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        TableMetadata orders = catalog.createTable(TableMetadata.define(
                "shop",
                "orders",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals(2, orders.tableId().orElseThrow());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void createTableIgnoresIncomingIds() {
        CatalogManager catalog = catalogWithShop();
        TableMetadata withIds = new TableMetadata(
                99,
                "shop",
                "users",
                List.of(new ColumnMetadata(7, "id", ColumnType.INT, true))
        );

        TableMetadata created = catalog.createTable(withIds);

        assertEquals(1, created.tableId().orElseThrow());
        assertEquals(1, created.columns().get(0).columnId().orElseThrow());
    }

    @Test
    void restoreKeepsIdsAndContinuesAllocation() {
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        catalog.replaceAll(List.of(new TableMetadata(
                4,
                "shop",
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true))
        )));

        assertEquals(4, catalog.getTable("shop", "users").orElseThrow().tableId().orElseThrow());

        TableMetadata orders = catalog.createTable(TableMetadata.define(
                "shop",
                "orders",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        assertEquals(5, orders.tableId().orElseThrow());
    }

    @Test
    void dropTableRemovesTableAndRejectsMissing() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());
        catalog.dropTable("shop", "users");

        assertFalse(catalog.tableExists("shop", "users"));

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropTable("shop", "users")
        );
        assertEquals("table does not exist: shop.users", ex.getMessage());
    }

    @Test
    void createDatabaseIsLookupableAndRejectsDuplicates() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");

        assertTrue(catalog.databaseExists("shop"));
        assertEquals(List.of("shop"), catalog.allDatabases());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createDatabase("shop")
        );
        assertEquals("database already exists: shop", ex.getMessage());
    }

    @Test
    void dropDatabaseRemovesNameAndRejectsMissing() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        catalog.dropDatabase("shop");

        assertFalse(catalog.databaseExists("shop"));
        assertTrue(catalog.allDatabases().isEmpty());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropDatabase("shop")
        );
        assertEquals("database does not exist: shop", ex.getMessage());
    }

    @Test
    void dropDatabaseRejectsNonEmptyDatabase() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropDatabase("shop")
        );
        assertEquals("database is not empty: shop", ex.getMessage());
        assertTrue(catalog.databaseExists("shop"));
        assertTrue(catalog.tableExists("shop", "users"));
    }

    private static CatalogManager catalogWithShop() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        return catalog;
    }

    private static TableMetadata usersDefinition() {
        return TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        );
    }
}
