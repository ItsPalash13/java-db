package com.example.database.processor.executor;

import com.example.database.processor.planner.DescribeTablePlan;
import com.example.database.processor.planner.ShowDatabasesPlan;
import com.example.database.processor.planner.ShowTablesPlan;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescribeExecutorTest {

    private DefaultCatalogManager catalog;
    private DescribeExecutor executor;

    @BeforeEach
    void setUp() {
        catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        executor = new DescribeExecutor(catalog);
    }

    @Test
    void describeTableReturnsColumnMetadataRows() {
        QueryResult result = executor.execute(new DescribeTablePlan("shop", "users"));

        assertTrue(result.hasResultSet());
        assertEquals(
                List.of(
                        List.of("id", "INT", "YES"),
                        List.of("name", "VARCHAR", "YES")
                ),
                result.toWireResponse().messages().stream()
                        .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                        .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .rows()
        );
    }

    @Test
    void showDatabasesListsCatalogDatabases() {
        QueryResult result = executor.execute(new ShowDatabasesPlan());

        assertTrue(result.hasResultSet());
        assertEquals(List.of(List.of("shop")), rows(result));
    }

    @Test
    void showTablesFiltersByDatabase() {
        catalog.createTable(TableMetadata.define("shop", "orders", List.of(ColumnMetadata.define("id", ColumnType.INT))));

        QueryResult result = executor.execute(new ShowTablesPlan("shop"));

        assertTrue(result.hasResultSet());
        assertEquals(List.of(List.of("orders"), List.of("users")), rows(result));
    }

    private static List<List<Object>> rows(QueryResult result) {
        return result.toWireResponse().messages().stream()
                .filter(com.example.database.network.wire.WireMessage.ResultSet.class::isInstance)
                .map(com.example.database.network.wire.WireMessage.ResultSet.class::cast)
                .findFirst()
                .orElseThrow()
                .rows();
    }
}
