package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.UnresolvedQuery;

import java.util.Objects;

/**
 * Phase 1.6: CREATE TABLE only. Copies names/types onto {@link CreateTablePlan}.
 * Does not read or write {@code CatalogManager} or {@code PhysicalStorage}.
 */
public final class DefaultQueryPlanner implements QueryPlanner {

    @Override
    public ExecutionPlan plan(AnalyzedQuery analyzed) {
        Objects.requireNonNull(analyzed, "analyzed");
        if (analyzed instanceof AnalyzedCreateTable createTable) {
            return new CreateTablePlan(createTable.table(), createTable.columns());
        }
        if (analyzed instanceof UnresolvedQuery unresolved) {
            return new UnresolvedPlan(unresolved);
        }
        throw new IllegalArgumentException("unsupported analyzed query: " + analyzed);
    }
}
