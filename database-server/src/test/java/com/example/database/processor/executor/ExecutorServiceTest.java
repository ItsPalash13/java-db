package com.example.database.processor.executor;

import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.QueryType;
import com.example.database.processor.planner.UnresolvedPlan;
import com.example.database.processor.analyser.UnresolvedQuery;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorServiceTest {

    @Test
    void dispatchesCreateTableToCommandExecutor() {
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        ExecutorRegistry registry = new ExecutorRegistry();
        registry.register(QueryType.CREATE_TABLE, new CommandExecutor(catalog));
        ExecutorService executorService = new ExecutorService(registry);

        QueryResult result = executorService.execute(new CreateTablePlan(
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals("OK", result.toResponse());
        assertTrue(catalog.tableExists("users"));
    }

    @Test
    void missingExecutorIsAnExecutionError() {
        ExecutorService executorService = new ExecutorService(new ExecutorRegistry());
        UnresolvedPlan plan = new UnresolvedPlan(
                new UnresolvedQuery(new SelectQuery(true, List.of(), "t", null))
        );

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> executorService.execute(plan)
        );
        assertEquals("no executor registered for UNRESOLVED", ex.getMessage());
    }
}
