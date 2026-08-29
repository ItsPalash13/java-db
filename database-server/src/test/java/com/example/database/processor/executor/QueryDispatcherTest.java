package com.example.database.processor.executor;

import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.QueryType;
import com.example.database.processor.planner.UnresolvedPlan;
import com.example.database.processor.analyser.UnresolvedQuery;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.transaction.DefaultTransactionManager;
import com.example.database.storage.wal.DefaultWALManager;
import com.example.database.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryDispatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void dispatchesCreateTableToCommandExecutor() {
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        WALManager wal = new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)));
        ExecutorRegistry registry = new ExecutorRegistry();
        registry.register(
                QueryType.CREATE_TABLE,
                new CommandExecutor(
                        catalog,
                        new DefaultTransactionManager(wal),
                        new DefaultLockManager(),
                        wal
                )
        );
        QueryDispatcher dispatcher = new QueryDispatcher(registry);

        QueryResult result = dispatcher.execute(new CreateTablePlan(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        assertEquals("OK", result.toResponse());
        assertTrue(catalog.tableExists("shop", "users"));
    }

    @Test
    void missingExecutorIsAnExecutionError() {
        QueryDispatcher dispatcher = new QueryDispatcher(new ExecutorRegistry());
        UnresolvedPlan plan = new UnresolvedPlan(
                new UnresolvedQuery(new SelectQuery(true, List.of(), new QualifiedTable("shop", "t"), null))
        );

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> dispatcher.execute(plan)
        );
        assertEquals("no executor registered for UNRESOLVED", ex.getMessage());
    }
}
