package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedAddColumn;
import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateIndex;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDelete;
import com.example.database.processor.analyser.AnalyzedDropColumn;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.AnalyzedDropIndex;
import com.example.database.processor.analyser.AnalyzedDropTable;
import com.example.database.processor.analyser.AnalyzedInsert;
import com.example.database.processor.analyser.AnalyzedSelect;
import com.example.database.processor.analyser.AnalyzedUpdate;
import com.example.database.processor.analyser.ResolvedAssignment;
import com.example.database.processor.analyser.ResolvedInsertValue;
import com.example.database.processor.analyser.ResolvedProjection;
import com.example.database.processor.analyser.UnresolvedQuery;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultQueryPlannerTest {

    private final QueryPlanner planner = new DefaultQueryPlanner();

    @Test
    void plansCreateTableWithSameNamesAndTypes() {
        List<ColumnMetadata> columns = List.of(
                ColumnMetadata.define("id", ColumnType.INT),
                ColumnMetadata.define("name", ColumnType.VARCHAR)
        );
        AnalyzedCreateTable analyzed = new AnalyzedCreateTable("shop", "users", columns);

        CreateTablePlan plan = assertInstanceOf(CreateTablePlan.class, planner.plan(analyzed));

        assertEquals(QueryType.CREATE_TABLE, plan.queryType());
        assertEquals("shop", plan.database());
        assertEquals("users", plan.table());
        assertEquals(columns, plan.columns());
        assertEquals(new CreateTablePlan("shop", "users", columns), plan);
    }

    @Test
    void plansDropTable() {
        DropTablePlan plan = assertInstanceOf(
                DropTablePlan.class,
                planner.plan(new AnalyzedDropTable("shop", "users"))
        );
        assertEquals(QueryType.DROP_TABLE, plan.queryType());
        assertEquals("shop", plan.database());
        assertEquals("users", plan.table());
    }

    @Test
    void plansCreateAndDropDatabase() {
        CreateDatabasePlan create = assertInstanceOf(
                CreateDatabasePlan.class,
                planner.plan(new AnalyzedCreateDatabase("shop"))
        );
        assertEquals(QueryType.CREATE_DATABASE, create.queryType());
        assertEquals("shop", create.database());

        DropDatabasePlan drop = assertInstanceOf(
                DropDatabasePlan.class,
                planner.plan(new AnalyzedDropDatabase("shop"))
        );
        assertEquals(QueryType.DROP_DATABASE, drop.queryType());
        assertEquals("shop", drop.database());
    }

    @Test
    void plansAddColumn() {
        ColumnMetadata age = ColumnMetadata.define("age", ColumnType.INT);
        AddColumnPlan plan = assertInstanceOf(
                AddColumnPlan.class,
                planner.plan(new AnalyzedAddColumn("shop", "users", age))
        );
        assertEquals(QueryType.ADD_COLUMN, plan.queryType());
        assertEquals("shop", plan.database());
        assertEquals("users", plan.table());
        assertEquals(age, plan.column());
    }

    @Test
    void plansDropColumn() {
        DropColumnPlan plan = assertInstanceOf(
                DropColumnPlan.class,
                planner.plan(new AnalyzedDropColumn("shop", "users", "name"))
        );
        assertEquals(QueryType.DROP_COLUMN, plan.queryType());
        assertEquals("shop", plan.database());
        assertEquals("users", plan.table());
        assertEquals("name", plan.column());
    }

    @Test
    void plansCreateAndDropIndex() {
        CreateIndexPlan create = assertInstanceOf(
                CreateIndexPlan.class,
                planner.plan(new AnalyzedCreateIndex("shop", "users", "idx_users_id", List.of(1)))
        );
        assertEquals(QueryType.CREATE_INDEX, create.queryType());
        assertEquals(List.of(1), create.columnIds());

        DropIndexPlan drop = assertInstanceOf(
                DropIndexPlan.class,
                planner.plan(new AnalyzedDropIndex("shop", "users", "idx_users_id"))
        );
        assertEquals(QueryType.DROP_INDEX, drop.queryType());
    }

    @Test
    void plansSelectAsTableScanWithoutMatchingIndex() {
        ColumnMetadata id = new ColumnMetadata(1, "id", ColumnType.INT, true);
        ColumnMetadata name = new ColumnMetadata(2, "name", ColumnType.VARCHAR, true);
        AnalyzedSelect analyzed = new AnalyzedSelect(
                "shop",
                "users",
                List.of(ResolvedProjection.column(id), ResolvedProjection.column(name)),
                null,
                List.of(id, name),
                List.of()
        );

        SelectPlan plan = assertInstanceOf(SelectPlan.class, planner.plan(analyzed));
        assertEquals(QueryType.SELECT, plan.queryType());
        assertEquals("shop", plan.database());
        assertEquals("users", plan.table());
        assertEquals(2, plan.projections().size());
        assertEquals(AccessPath.tableScan(), plan.accessPath());
    }

    @Test
    void plansSelectAsIndexScanWhenEqualityMatchesLeadingIndexColumn() {
        ColumnMetadata id = new ColumnMetadata(1, "id", ColumnType.INT, true);
        ColumnMetadata name = new ColumnMetadata(2, "name", ColumnType.VARCHAR, true);
        BinaryExpression where = new BinaryExpression(
                new ColumnExpression("id"),
                TokenCatalog.EQ,
                new LiteralExpression(1L)
        );
        AnalyzedSelect analyzed = new AnalyzedSelect(
                "shop",
                "users",
                List.of(ResolvedProjection.column(id)),
                where,
                List.of(id, name),
                List.of(IndexMetadata.define("idx_users_id", List.of(1)))
        );

        SelectPlan plan = assertInstanceOf(SelectPlan.class, planner.plan(analyzed));
        assertEquals(AccessPath.indexScan("idx_users_id"), plan.accessPath());
    }

    @Test
    void plansInsertUpdateDelete() {
        ColumnMetadata id = new ColumnMetadata(1, "id", ColumnType.INT, true);
        InsertPlan insert = assertInstanceOf(
                InsertPlan.class,
                planner.plan(new AnalyzedInsert(
                        "shop",
                        "users",
                        List.of(new ResolvedInsertValue(1, ColumnType.INT, 1))
                ))
        );
        assertEquals(QueryType.INSERT, insert.queryType());
        assertEquals(1, insert.values().get(0).value());

        BinaryExpression where = new BinaryExpression(
                new ColumnExpression("id"),
                TokenCatalog.EQ,
                new LiteralExpression(1L)
        );
        UpdatePlan update = assertInstanceOf(
                UpdatePlan.class,
                planner.plan(new AnalyzedUpdate(
                        "shop",
                        "users",
                        List.of(new ResolvedAssignment(2, ColumnType.VARCHAR, new LiteralExpression("Bob"))),
                        where,
                        List.of(id),
                        List.of(IndexMetadata.define("idx_users_id", List.of(1)))
                ))
        );
        assertEquals(QueryType.UPDATE, update.queryType());
        assertEquals(AccessPath.indexScan("idx_users_id"), update.accessPath());

        DeletePlan delete = assertInstanceOf(
                DeletePlan.class,
                planner.plan(new AnalyzedDelete("shop", "users", null, List.of(id), List.of()))
        );
        assertEquals(QueryType.DELETE, delete.queryType());
        assertEquals(AccessPath.tableScan(), delete.accessPath());
    }

    @Test
    void passesThroughUnresolvedQueries() {
        UnresolvedQuery unresolved = new UnresolvedQuery(
                new SelectQuery(true, List.of(), new QualifiedTable("shop", "users"), null)
        );

        UnresolvedPlan plan = assertInstanceOf(UnresolvedPlan.class, planner.plan(unresolved));

        assertEquals(QueryType.UNRESOLVED, plan.queryType());
        assertSame(unresolved, plan.source());
    }
}
