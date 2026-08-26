package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.UnresolvedQuery;

import java.util.Objects;

/**
 * Maps analyzed DDL onto command plans. Does not read or write catalog or disk.
 */
public final class DefaultQueryPlanner implements QueryPlanner {

    @Override
    public ExecutionPlan plan(AnalyzedQuery analyzed) {
        Objects.requireNonNull(analyzed, "analyzed");
        if (analyzed instanceof AnalyzedCreateTable createTable) {
            return new CreateTablePlan(createTable.table(), createTable.columns());
        }
        if (analyzed instanceof AnalyzedCreateDatabase createDatabase) {
            return new CreateDatabasePlan(createDatabase.database());
        }
        if (analyzed instanceof AnalyzedDropDatabase dropDatabase) {
            return new DropDatabasePlan(dropDatabase.database());
        }
        if (analyzed instanceof UnresolvedQuery unresolved) {
            return new UnresolvedPlan(unresolved);
        }
        throw new IllegalArgumentException("unsupported analyzed query: " + analyzed);
    }
}
