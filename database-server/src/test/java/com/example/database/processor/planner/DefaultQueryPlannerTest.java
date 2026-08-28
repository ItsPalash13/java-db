package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedAddColumn;
import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateIndex;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDropColumn;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.AnalyzedDropIndex;
import com.example.database.processor.analyser.AnalyzedDropTable;
import com.example.database.processor.analyser.UnresolvedQuery;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
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
    void passesThroughUnresolvedQueries() {
        UnresolvedQuery unresolved = new UnresolvedQuery(
                new SelectQuery(true, List.of(), new QualifiedTable("shop", "users"), null)
        );

        UnresolvedPlan plan = assertInstanceOf(UnresolvedPlan.class, planner.plan(unresolved));

        assertEquals(QueryType.UNRESOLVED, plan.queryType());
        assertSame(unresolved, plan.source());
    }
}
