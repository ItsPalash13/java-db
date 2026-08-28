package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.AlterTableQuery;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.DropIndexQuery;
import com.example.database.processor.parser.ast.query.DropTableQuery;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.IndexMetadata;
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
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedQuery analyzed = analyser.analyse(usersCreateTableQuery());

        AnalyzedCreateTable createTable = assertInstanceOf(AnalyzedCreateTable.class, analyzed);
        assertEquals("shop", createTable.database());
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
    void rejectsCreateTableWhenDatabaseMissing() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(usersCreateTableQuery())
        );
        assertEquals("database does not exist: shop", ex.getMessage());
    }

    @Test
    void rejectsCreateTableWhenTableAlreadyExists() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(usersCreateTableQuery())
        );
        assertEquals("table already exists: shop.users", ex.getMessage());
        assertEquals("ERROR: table already exists: shop.users", ex.toResponse());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void rejectsDuplicateColumnNames() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("id", ColumnSqlType.INT),
                new ColumnDefinition("id", ColumnSqlType.VARCHAR)
        );
        CreateTableQuery query = new CreateTableQuery(shopUsers(), columns);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(query)
        );
        assertEquals("duplicate column name: id", ex.getMessage());
        assertTrue(catalog.allTables().isEmpty());
    }

    @Test
    void rejectsEmptyColumnList() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new CreateTableQuery(shopUsers(), List.of()))
        );
        assertEquals("table must have at least one column: shop.users", ex.getMessage());
    }

    @Test
    void acceptsDropTableWhenTableExistsWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedDropTable analyzed = assertInstanceOf(
                AnalyzedDropTable.class,
                analyser.analyse(dropUsersTableQuery())
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void rejectsDropTableWhenMissing() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(dropUsersTableQuery())
        );
        assertEquals("table does not exist: shop.users", ex.getMessage());
    }

    @Test
    void passesThroughSelectAsUnresolved() {
        CatalogManager catalog = new DefaultCatalogManager();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);
        SelectQuery select = new SelectQuery(true, List.of(), shopUsers(), null);

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

    @Test
    void rejectsDropDatabaseWhenTablesRemain() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new DropDatabaseQuery("shop"))
        );
        assertEquals("database is not empty: shop", ex.getMessage());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void acceptsAddColumnWhenTableExistsWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedAddColumn analyzed = assertInstanceOf(
                AnalyzedAddColumn.class,
                analyser.analyse(addAgeColumnQuery())
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals("age", analyzed.column().name());
        assertEquals(ColumnType.INT, analyzed.column().type());
        assertTrue(analyzed.column().columnId().isEmpty());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().columns().size());
    }

    @Test
    void rejectsAddColumnWhenTableMissing() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(addAgeColumnQuery())
        );
        assertEquals("table does not exist: shop.users", ex.getMessage());
    }

    @Test
    void rejectsAddColumnWhenNameAlreadyExists() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("age", ColumnType.INT)
                )
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(addAgeColumnQuery())
        );
        assertEquals("duplicate column name: age", ex.getMessage());
    }

    @Test
    void acceptsDropColumnWhenColumnExistsWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedDropColumn analyzed = assertInstanceOf(
                AnalyzedDropColumn.class,
                analyser.analyse(dropAgeColumnQuery())
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals("name", analyzed.column());
        assertEquals(2, catalog.getTable("shop", "users").orElseThrow().columns().size());
    }

    @Test
    void rejectsDropColumnWhenMissing() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new AlterTableQuery(
                        shopUsers(),
                        AlterTableQuery.Action.DROP_COLUMN,
                        "age",
                        null
                ))
        );
        assertEquals("column does not exist: age", ex.getMessage());
    }

    @Test
    void rejectsDropColumnWhenLastColumn() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new AlterTableQuery(
                        shopUsers(),
                        AlterTableQuery.Action.DROP_COLUMN,
                        "id",
                        null
                ))
        );
        assertEquals("cannot drop last column: id", ex.getMessage());
    }

    @Test
    void rejectsDropColumnWhenIndexReferencesColumn() {
        CatalogManager catalog = catalogWithShop();
        TableMetadata users = catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new AlterTableQuery(
                        shopUsers(),
                        AlterTableQuery.Action.DROP_COLUMN,
                        "id",
                        null
                ))
        );
        assertEquals("index references column: idx_users_id", ex.getMessage());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().indexes().size());
    }

    @Test
    void acceptsCreateIndexWhenTableAndColumnsExistWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedCreateIndex analyzed = assertInstanceOf(
                AnalyzedCreateIndex.class,
                analyser.analyse(createIndexQuery())
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals("idx_users_id", analyzed.index());
        assertEquals(List.of(1), analyzed.columnIds());
        assertTrue(catalog.getTable("shop", "users").orElseThrow().indexes().isEmpty());
    }

    @Test
    void rejectsCreateIndexWhenColumnMissing() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new CreateIndexQuery(
                        "idx_users_id",
                        shopUsers(),
                        List.of("missing")
                ))
        );
        assertEquals("column does not exist: missing", ex.getMessage());
    }

    @Test
    void rejectsCreateIndexWhenNameAlreadyExists() {
        CatalogManager catalog = catalogWithShop();
        TableMetadata users = catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(createIndexQuery())
        );
        assertEquals("index already exists: idx_users_id", ex.getMessage());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().indexes().size());
    }

    @Test
    void acceptsDropIndexWhenNameExistsWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));
        catalog.createIndex("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedDropIndex analyzed = assertInstanceOf(
                AnalyzedDropIndex.class,
                analyser.analyse(new DropIndexQuery("idx_users_id"))
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals("idx_users_id", analyzed.index());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().indexes().size());
    }

    @Test
    void rejectsDropIndexWhenMissing() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new DropIndexQuery("idx_users_id"))
        );
        assertEquals("index does not exist: idx_users_id", ex.getMessage());
    }

    private static AlterTableQuery addAgeColumnQuery() {
        return new AlterTableQuery(
                shopUsers(),
                AlterTableQuery.Action.ADD_COLUMN,
                "age",
                ColumnSqlType.INT
        );
    }

    private static AlterTableQuery dropAgeColumnQuery() {
        return new AlterTableQuery(
                shopUsers(),
                AlterTableQuery.Action.DROP_COLUMN,
                "name",
                null
        );
    }

    private static CreateIndexQuery createIndexQuery() {
        return new CreateIndexQuery("idx_users_id", shopUsers(), List.of("id"));
    }

    private static CatalogManager catalogWithShop() {
        CatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        return catalog;
    }

    private static CreateTableQuery usersCreateTableQuery() {
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("id", ColumnSqlType.INT),
                new ColumnDefinition("name", ColumnSqlType.VARCHAR)
        );
        return new CreateTableQuery(shopUsers(), columns);
    }

    private static DropTableQuery dropUsersTableQuery() {
        QualifiedTable table = shopUsers();
        return new DropTableQuery(table);
    }

    private static QualifiedTable shopUsers() {
        return new QualifiedTable("shop", "users");
    }
}
