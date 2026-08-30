package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedAddColumn;
import com.example.database.processor.analyser.AnalyzedBegin;
import com.example.database.processor.analyser.AnalyzedCheckpoint;
import com.example.database.processor.analyser.AnalyzedCommit;
import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateIndex;
import com.example.database.processor.analyser.AnalyzedDropIndex;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.AnalyzedDropTable;
import com.example.database.processor.analyser.AnalyzedDescribeTable;
import com.example.database.processor.analyser.AnalyzedDropColumn;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.AnalyzedRollback;
import com.example.database.processor.analyser.AnalyzedShowDatabases;
import com.example.database.processor.analyser.AnalyzedShowTables;
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
            return new CreateTablePlan(createTable.database(), createTable.table(), createTable.columns());
        }
        if (analyzed instanceof AnalyzedDropTable dropTable) {
            return new DropTablePlan(dropTable.database(), dropTable.table());
        }
        if (analyzed instanceof AnalyzedCreateDatabase createDatabase) {
            return new CreateDatabasePlan(createDatabase.database());
        }
        if (analyzed instanceof AnalyzedDropDatabase dropDatabase) {
            return new DropDatabasePlan(dropDatabase.database());
        }
        if (analyzed instanceof AnalyzedAddColumn addColumn) {
            return new AddColumnPlan(addColumn.database(), addColumn.table(), addColumn.column());
        }
        if (analyzed instanceof AnalyzedDropColumn dropColumn) {
            return new DropColumnPlan(dropColumn.database(), dropColumn.table(), dropColumn.column());
        }
        if (analyzed instanceof AnalyzedCreateIndex createIndex) {
            return new CreateIndexPlan(
                    createIndex.database(),
                    createIndex.table(),
                    createIndex.index(),
                    createIndex.columnIds()
            );
        }
        if (analyzed instanceof AnalyzedDropIndex dropIndex) {
            return new DropIndexPlan(dropIndex.database(), dropIndex.table(), dropIndex.index());
        }
        if (analyzed instanceof AnalyzedBegin) {
            return new BeginPlan();
        }
        if (analyzed instanceof AnalyzedCommit) {
            return new CommitPlan();
        }
        if (analyzed instanceof AnalyzedRollback) {
            return new RollbackPlan();
        }
        if (analyzed instanceof AnalyzedCheckpoint) {
            return new CheckpointPlan();
        }
        if (analyzed instanceof AnalyzedDescribeTable describe) {
            return new DescribeTablePlan(describe.database(), describe.table());
        }
        if (analyzed instanceof AnalyzedShowDatabases) {
            return new ShowDatabasesPlan();
        }
        if (analyzed instanceof AnalyzedShowTables showTables) {
            return new ShowTablesPlan(showTables.database());
        }
        if (analyzed instanceof UnresolvedQuery unresolved) {
            return new UnresolvedPlan(unresolved);
        }
        throw new IllegalArgumentException("unsupported analyzed query: " + analyzed);
    }
}
