package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.UnresolvedQuery;
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
        AnalyzedCreateTable analyzed = new AnalyzedCreateTable("users", columns);

        CreateTablePlan plan = assertInstanceOf(CreateTablePlan.class, planner.plan(analyzed));

        assertEquals(QueryType.CREATE_TABLE, plan.queryType());
        assertEquals("users", plan.table());
        assertEquals(columns, plan.columns());
        assertEquals(new CreateTablePlan("users", columns), plan);
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
    void passesThroughUnresolvedQueries() {
        UnresolvedQuery unresolved = new UnresolvedQuery(
                new SelectQuery(true, List.of(), "users", null)
        );

        UnresolvedPlan plan = assertInstanceOf(UnresolvedPlan.class, planner.plan(unresolved));

        assertEquals(QueryType.UNRESOLVED, plan.queryType());
        assertSame(unresolved, plan.source());
    }
}
