package com.example.database.processor.analyser;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Assignment;
import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.processor.parser.ast.query.AlterTableQuery;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DeleteQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.DropIndexQuery;
import com.example.database.processor.parser.ast.query.DropTableQuery;
import com.example.database.processor.parser.ast.query.InsertQuery;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.processor.parser.ast.query.UpdateQuery;
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
    void acceptsSelectStarWhenTableExistsWithoutMutatingCatalog() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedSelect analyzed = assertInstanceOf(
                AnalyzedSelect.class,
                analyser.analyse(new SelectQuery(true, List.of(), shopUsers(), null))
        );
        assertEquals("shop", analyzed.database());
        assertEquals("users", analyzed.table());
        assertEquals(2, analyzed.projections().size());
        assertEquals("id", analyzed.projections().get(0).name().orElseThrow());
        assertEquals(1, analyzed.projections().get(0).columnId().orElseThrow());
        assertEquals("name", analyzed.projections().get(1).name().orElseThrow());
        assertTrue(analyzed.where().isEmpty());
        assertEquals(1, catalog.allTables().size());
    }

    @Test
    void rejectsSelectWhenTableMissing() {
        CatalogManager catalog = catalogWithShop();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new SelectQuery(true, List.of(), shopUsers(), null))
        );
        assertEquals("table does not exist: shop.users", ex.getMessage());
    }

    @Test
    void rejectsSelectUnknownColumn() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new SelectQuery(
                        false,
                        List.of(new ColumnExpression("missing")),
                        shopUsers(),
                        null
                ))
        );
        assertEquals("column does not exist: missing", ex.getMessage());
    }

    @Test
    void rejectsWhereUnknownColumnAndTypeMismatch() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException unknown = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new SelectQuery(
                        true,
                        List.of(),
                        shopUsers(),
                        new BinaryExpression(
                                new ColumnExpression("missing"),
                                TokenCatalog.EQ,
                                new LiteralExpression(1L)
                        )
                ))
        );
        assertEquals("column does not exist: missing", unknown.getMessage());

        AnalysisException mismatch = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new SelectQuery(
                        true,
                        List.of(),
                        shopUsers(),
                        new BinaryExpression(
                                new ColumnExpression("id"),
                                TokenCatalog.EQ,
                                new LiteralExpression("Ada")
                        )
                ))
        );
        assertEquals("type mismatch: INT vs VARCHAR", mismatch.getMessage());
    }

    @Test
    void acceptsInsertMatchingArityAndFillsOmittedNullable() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedInsert full = assertInstanceOf(
                AnalyzedInsert.class,
                analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of(),
                        List.of(new LiteralExpression(1L), new LiteralExpression("Ada"))
                ))
        );
        assertEquals(2, full.values().size());
        assertEquals(1, full.values().get(0).value());
        assertEquals("Ada", full.values().get(1).value());

        AnalyzedInsert partial = assertInstanceOf(
                AnalyzedInsert.class,
                analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of("id"),
                        List.of(new LiteralExpression(2L))
                ))
        );
        assertEquals(2, partial.values().size());
        assertEquals(2, partial.values().get(0).value());
        assertEquals(null, partial.values().get(1).value());
    }

    @Test
    void rejectsInsertWrongArityTypeAndNonLiteral() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException arity = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of(),
                        List.of(new LiteralExpression(1L))
                ))
        );
        assertEquals("expected 2 values but got 1", arity.getMessage());

        AnalysisException type = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of(),
                        List.of(new LiteralExpression("x"), new LiteralExpression("Ada"))
                ))
        );
        assertEquals("type mismatch: expected INT but got VARCHAR", type.getMessage());

        AnalysisException notLiteral = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of(),
                        List.of(new ColumnExpression("id"), new LiteralExpression("Ada"))
                ))
        );
        assertEquals("INSERT values must be literals", notLiteral.getMessage());
    }

    @Test
    void rejectsInsertOmittedNonNullableColumn() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT, false),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new InsertQuery(
                        shopUsers(),
                        List.of("name"),
                        List.of(new LiteralExpression("Ada"))
                ))
        );
        assertEquals("column is not nullable: id", ex.getMessage());
    }

    @Test
    void acceptsUpdateAndDeleteWhenTableExists() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalyzedUpdate update = assertInstanceOf(
                AnalyzedUpdate.class,
                analyser.analyse(new UpdateQuery(
                        shopUsers(),
                        List.of(new Assignment("name", new LiteralExpression("Bob"))),
                        new BinaryExpression(
                                new ColumnExpression("id"),
                                TokenCatalog.EQ,
                                new LiteralExpression(1L)
                        )
                ))
        );
        assertEquals(2, update.assignments().get(0).columnId());
        assertTrue(update.where().isPresent());

        AnalyzedDelete delete = assertInstanceOf(
                AnalyzedDelete.class,
                analyser.analyse(new DeleteQuery(shopUsers(), null))
        );
        assertEquals("users", delete.table());
        assertTrue(delete.where().isEmpty());
    }

    @Test
    void rejectsUpdateUnknownColumn() {
        CatalogManager catalog = catalogWithUsers();
        DefaultQueryAnalyser analyser = new DefaultQueryAnalyser(catalog);

        AnalysisException ex = assertThrows(
                AnalysisException.class,
                () -> analyser.analyse(new UpdateQuery(
                        shopUsers(),
                        List.of(new Assignment("missing", new LiteralExpression(1L))),
                        null
                ))
        );
        assertEquals("column does not exist: missing", ex.getMessage());
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
        catalog.createTable(TableMetadata.define(
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
        assertEquals(false, analyzed.unique());
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
        catalog.createTable(TableMetadata.define(
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

    private static CatalogManager catalogWithUsers() {
        CatalogManager catalog = catalogWithShop();
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        return catalog;
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
