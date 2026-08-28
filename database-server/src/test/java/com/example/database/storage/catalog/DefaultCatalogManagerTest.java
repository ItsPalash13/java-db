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
    void addColumnAssignsNextColumnId() {
        CatalogManager catalog = catalogWithShop();
        TableMetadata created = catalog.createTable(usersDefinition());

        TableMetadata updated = catalog.addColumn(
                "shop",
                "users",
                ColumnMetadata.define("age", ColumnType.INT)
        );

        assertEquals(created.tableId(), updated.tableId());
        assertEquals(3, updated.columns().size());
        ColumnMetadata age = updated.columns().get(2);
        assertEquals("age", age.name());
        assertEquals(ColumnType.INT, age.type());
        assertEquals(3, age.columnId().orElseThrow());
        assertTrue(age.nullable());
        assertEquals(updated, catalog.getTable("shop", "users").orElseThrow());
    }

    @Test
    void addColumnRejectsDuplicateName() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.addColumn("shop", "users", ColumnMetadata.define("id", ColumnType.BOOLEAN))
        );
        assertEquals("duplicate column name: id", ex.getMessage());
        assertEquals(2, catalog.getTable("shop", "users").orElseThrow().columns().size());
    }

    @Test
    void addColumnRejectsMissingTable() {
        CatalogManager catalog = catalogWithShop();

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.addColumn("shop", "users", ColumnMetadata.define("age", ColumnType.INT))
        );
        assertEquals("table does not exist: shop.users", ex.getMessage());
    }

    @Test
    void dropColumnRemovesColumnByName() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        TableMetadata updated = catalog.dropColumn("shop", "users", "name");

        assertEquals(1, updated.columns().size());
        assertEquals("id", updated.columns().get(0).name());
        assertEquals(1, updated.columns().get(0).columnId().orElseThrow());
        assertEquals(updated, catalog.getTable("shop", "users").orElseThrow());
    }

    @Test
    void dropColumnRejectsMissingColumn() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropColumn("shop", "users", "age")
        );
        assertEquals("column does not exist: age", ex.getMessage());
        assertEquals(2, catalog.getTable("shop", "users").orElseThrow().columns().size());
    }

    @Test
    void dropColumnRejectsLastColumn() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropColumn("shop", "users", "id")
        );
        assertEquals("cannot drop last column: id", ex.getMessage());
    }

    @Test
    void dropColumnRejectsWhenIndexReferencesColumn() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());
        catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)));

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.dropColumn("shop", "users", "id")
        );
        assertEquals("index references column: idx_users_id", ex.getMessage());
    }

    @Test
    void createIndexAddsDefinitionAndDropIndexRemovesIt() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        TableMetadata updated = catalog.createIndex(
                "shop",
                "users",
                IndexMetadata.define("idx_users_id", List.of(1))
        );
        assertEquals(1, updated.indexes().size());
        assertEquals("idx_users_id", updated.indexes().get(0).name());
        assertEquals(List.of(1), updated.indexes().get(0).columnIds());

        catalog.dropIndex("idx_users_id");
        assertTrue(catalog.getTable("shop", "users").orElseThrow().indexes().isEmpty());
    }

    @Test
    void createIndexRejectsDuplicateNameAndUnknownColumnId() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());
        catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)));

        CatalogException duplicate = assertThrows(
                CatalogException.class,
                () -> catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(2)))
        );
        assertEquals("index already exists: idx_users_id", duplicate.getMessage());

        CatalogException unknownColumn = assertThrows(
                CatalogException.class,
                () -> catalog.createIndex("shop", "users", IndexMetadata.define("idx_name", List.of(99)))
        );
        assertEquals("index references unknown column id: 99", unknownColumn.getMessage());
    }

    @Test
    void dropIndexRejectsMissingAndAmbiguousNames() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(usersDefinition());

        CatalogException missing = assertThrows(
                CatalogException.class,
                () -> catalog.dropIndex("idx_users_id")
        );
        assertEquals("index does not exist: idx_users_id", missing.getMessage());

        catalog.createIndex("shop", "users", IndexMetadata.define("shared", List.of(1)));
        catalog.createDatabase("app");
        catalog.createTable(TableMetadata.define(
                "app",
                "orders",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        catalog.createIndex("app", "orders", IndexMetadata.define("shared", List.of(1)));

        CatalogException ambiguous = assertThrows(
                CatalogException.class,
                () -> catalog.dropIndex("shared")
        );
        assertEquals("ambiguous index name: shared", ambiguous.getMessage());
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
