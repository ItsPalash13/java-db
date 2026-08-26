package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryAnalyserTest {

    @Test
    void acceptsNewCreateTableWithoutMutatingCatalog() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedQuery analyzed = analyser.analyse(usersCreateTableQuery());

        AnalyzedCreateTable createTable = assertInstanceOf(AnalyzedCreateTable.class, analyzed);
        assertEquals("users", createTable.table());
        assertEquals(
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                ),
                createTable.columns()
        );
        assertTrue(catalog.allTables().isEmpty());
    }

    @Test
    void rejectsCreateTableWhenTableAlreadyExists() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createTable(TableMetadata.define(
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(usersCreateTableQuery())
        );
        assertEquals("table already exists: users", ex.getMessage());
        assertEquals("ERROR: table already exists: users", ex.toResponse());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void rejectsDuplicateColumnNames() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("id", ColumnSqlType.INT),
                new ColumnDefinition("id", ColumnSqlType.VARCHAR)
        );
        CreateTableQuery query = new CreateTableQuery("users", columns);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(query)
        );
        assertEquals("duplicate column name: id", ex.getMessage());
        assertTrue(catalog.allTables().isEmpty());
    }

    @Test
    void rejectsEmptyColumnList() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new CreateTableQuery("users", List.of()))
        );
        assertEquals("table must have at least one column: users", ex.getMessage());
    }

    @Test
    void passesThroughSelectAsUnresolved() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);
        SelectQuery select = new SelectQuery(true, List.of(), "users", null);

        UnresolvedQuery unresolved = assertInstanceOf(
                UnresolvedQuery.class,
                analyser.analyse(select)
        );
        assertEquals(select, unresolved.source());
    }

    @Test
    void acceptsCreateDatabaseWithoutMutatingCatalog() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedCreateDatabase analyzed = assertInstanceOf(
                AnalyzedCreateDatabase.class,
                analyser.analyse(new CreateDatabaseQuery("shop"))
        );
        assertEquals("shop", analyzed.database());
        assertTrue(catalog.allDatabases().isEmpty());
    }

    @Test
    void rejectsCreateDatabaseWhenNameExists() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new CreateDatabaseQuery("shop"))
        );
        assertEquals("database already exists: shop", ex.getMessage());
        assertEquals(1, catalog.allDatabases().size());
    }

    @Test
    void acceptsDropDatabaseWhenNameExists() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedDropDatabase analyzed = assertInstanceOf(
                AnalyzedDropDatabase.class,
                analyser.analyse(new DropDatabaseQuery("shop"))
        );
        assertEquals("shop", analyzed.database());
        assertEquals(List.of("shop"), catalog.allDatabases());
    }

    @Test
    void rejectsDropDatabaseWhenMissing() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new DropDatabaseQuery("shop"))
        );
        assertEquals("database does not exist: shop", ex.getMessage());
    }

    private static CreateTableQuery usersCreateTableQuery() {
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("id", ColumnSqlType.INT),
                new ColumnDefinition("name", ColumnSqlType.VARCHAR)
        );
        return new CreateTableQuery("users", columns);
    }
}
