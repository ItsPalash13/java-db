package com.example.database.processor.executor;

import com.example.database.processor.planner.CreateTablePlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.storage.catalog.CatalogException;
import com.example.database.storage.catalog.CatalogManager;
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
        if (!(plan instanceof CreateTablePlan createTable)) {
            throw new ExecutionException(
                    "CommandExecutor cannot execute " + plan.queryType()
            );
        }
        try {
            // Ids are assigned here, not in the planner. Persist is inside createTable.
            catalogManager.createTable(
                    TableMetadata.define(createTable.table(), createTable.columns())
            );
        } catch (CatalogException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        return QueryResult.ok();
    }
}
