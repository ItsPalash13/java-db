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
        CatalogManager catalog = new DefaultCatalogManager();

        TableMetadata created = catalog.createTable(usersDefinition());

        assertEquals(1, created.tableId().orElseThrow());
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

        assertTrue(catalog.tableExists("users"));
        assertEquals(created, catalog.getTable("users").orElseThrow());
    }

    @Test
    void getTableIsEmptyWhenMissing() {
        CatalogManager catalog = new DefaultCatalogManager();
        assertTrue(catalog.getTable("users").isEmpty());
        assertFalse(catalog.tableExists("users"));
    }

    @Test
    void createTableRejectsDuplicateTableName() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createTable(usersDefinition());

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(usersDefinition())
        );
        assertEquals("table already exists: users", ex.getMessage());
        assertEquals(1, catalog.getTable("users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void createTableRejectsDuplicateColumnNames() {
        CatalogManager catalog = new DefaultCatalogManager();
        TableMetadata duplicateColumns = TableMetadata.define(
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
        assertFalse(catalog.tableExists("users"));
    }

    @Test
    void createTableRejectsEmptyColumnList() {
        CatalogManager catalog = new DefaultCatalogManager();

        CatalogException ex = assertThrows(
                CatalogException.class,
                () -> catalog.createTable(TableMetadata.define("users", List.of()))
        );
        assertEquals("table must have at least one column: users", ex.getMessage());
        assertFalse(catalog.tableExists("users"));
    }

    @Test
    void secondTableGetsNextTableId() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createTable(usersDefinition());

        TableMetadata orders = catalog.createTable(TableMetadata.define(
                "orders",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals(2, orders.tableId().orElseThrow());
        assertEquals(1, catalog.getTable("users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void createTableIgnoresIncomingIds() {
        CatalogManager catalog = new DefaultCatalogManager();
        TableMetadata withIds = new TableMetadata(
                99,
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
        catalog.replaceAll(List.of(new TableMetadata(
                4,
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true))
        )));

        assertEquals(4, catalog.getTable("users").orElseThrow().tableId().orElseThrow());

        TableMetadata orders = catalog.createTable(TableMetadata.define(
                "orders",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        assertEquals(5, orders.tableId().orElseThrow());
    }

    private static TableMetadata usersDefinition() {
        return TableMetadata.define(
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        );
    }
}
