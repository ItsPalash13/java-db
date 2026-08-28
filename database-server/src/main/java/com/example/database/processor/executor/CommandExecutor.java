package com.example.database.processor.executor;

import com.example.database.processor.planner.AddColumnPlan;
import com.example.database.processor.planner.CreateDatabasePlan;
import com.example.database.processor.planner.CreateIndexPlan;
import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.DropColumnPlan;
import com.example.database.processor.planner.DropDatabasePlan;
import com.example.database.processor.planner.DropIndexPlan;
import com.example.database.processor.planner.DropTablePlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.storage.catalog.CatalogException;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;

import java.util.Objects;

/**
 * DDL as a catalog state change, not a Volcano {@code next()} loop.
 * Writes schema here; the analyser only reads. No locks or WAL in Phase 1.
 */
public final class CommandExecutor implements QueryExecutor {

    private final CatalogManager catalogManager;

    public CommandExecutor(CatalogManager catalogManager) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try {
            if (plan instanceof CreateTablePlan createTable) {
                // Ids are assigned here, not in the planner. Persist is inside createTable.
                catalogManager.createTable(
                        TableMetadata.define(
                                createTable.database(),
                                createTable.table(),
                                createTable.columns()
                        )
                );
                return QueryResult.ok();
            }
            if (plan instanceof DropTablePlan dropTable) {
                catalogManager.dropTable(dropTable.database(), dropTable.table());
                return QueryResult.ok();
            }
            if (plan instanceof CreateDatabasePlan createDatabase) {
                catalogManager.createDatabase(createDatabase.database());
                return QueryResult.ok();
            }
            if (plan instanceof DropDatabasePlan dropDatabase) {
                catalogManager.dropDatabase(dropDatabase.database());
                return QueryResult.ok();
            }
            if (plan instanceof AddColumnPlan addColumn) {
                catalogManager.addColumn(
                        addColumn.database(),
                        addColumn.table(),
                        addColumn.column()
                );
                return QueryResult.ok();
            }
            if (plan instanceof DropColumnPlan dropColumn) {
                catalogManager.dropColumn(
                        dropColumn.database(),
                        dropColumn.table(),
                        dropColumn.column()
                );
                return QueryResult.ok();
            }
            if (plan instanceof CreateIndexPlan createIndex) {
                catalogManager.createIndex(
                        createIndex.database(),
                        createIndex.table(),
                        IndexMetadata.define(createIndex.index(), createIndex.columnIds())
                );
                return QueryResult.ok();
            }
            if (plan instanceof DropIndexPlan dropIndex) {
                catalogManager.dropIndex(dropIndex.index());
                return QueryResult.ok();
            }
        } catch (CatalogException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        throw new ExecutionException("CommandExecutor cannot execute " + plan.queryType());
    }
}
