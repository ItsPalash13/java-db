package com.example.database.processor.planner;

import com.example.database.processor.analyser.AnalyzedAddColumn;
import com.example.database.processor.analyser.AnalyzedBegin;
import com.example.database.processor.analyser.AnalyzedCheckpoint;
import com.example.database.processor.analyser.AnalyzedCommit;
import com.example.database.processor.analyser.AnalyzedCreateDatabase;
import com.example.database.processor.analyser.AnalyzedCreateIndex;
import com.example.database.processor.analyser.AnalyzedDelete;
import com.example.database.processor.analyser.AnalyzedDropIndex;
import com.example.database.processor.analyser.AnalyzedCreateTable;
import com.example.database.processor.analyser.AnalyzedDropDatabase;
import com.example.database.processor.analyser.AnalyzedDropTable;
import com.example.database.processor.analyser.AnalyzedDescribeTable;
import com.example.database.processor.analyser.AnalyzedDropColumn;
import com.example.database.processor.analyser.AnalyzedInsert;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.AnalyzedRollback;
import com.example.database.processor.analyser.AnalyzedSelect;
import com.example.database.processor.analyser.AnalyzedShowDatabases;
import com.example.database.processor.analyser.AnalyzedShowTables;
import com.example.database.processor.analyser.AnalyzedUpdate;
import com.example.database.processor.analyser.UnresolvedQuery;

import java.util.Objects;

/**
 * Maps analyzed DDL onto command plans and DML/DQL onto row plans.
 * Does not read or write catalog or disk — access path uses indexes already on the analyzed query.
 */
public final class DefaultQueryPlanner implements QueryPlanner {

    @Override
    public ExecutionPlan plan(AnalyzedQuery analyzed) {
        Objects.requireNonNull(analyzed, "analyzed");
        if (analyzed instanceof AnalyzedCreateTable createTable) {
            return new CreateTablePlan(createTable.database(), createTable.table(),
                    createTable.columns(), createTable.primaryKeyColumn());
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
                    createIndex.columnIds(),
                    createIndex.unique()
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
        if (analyzed instanceof AnalyzedSelect select) {
            AccessPathChoice path = AccessPathChooser.choose(
                    select.where().orElse(null),
                    select.columns(),
                    select.indexes()
            );
            return new SelectPlan(
                    select.database(),
                    select.table(),
                    select.projections(),
                    select.where().orElse(null),
                    select.columns(),
                    path
            );
        }
        if (analyzed instanceof AnalyzedInsert insert) {
            return new InsertPlan(insert.database(), insert.table(), insert.values());
        }
        if (analyzed instanceof AnalyzedUpdate update) {
            AccessPathChoice path = AccessPathChooser.choose(
                    update.where().orElse(null),
                    update.columns(),
                    update.indexes()
            );
            return new UpdatePlan(
                    update.database(),
                    update.table(),
                    update.assignments(),
                    update.where().orElse(null),
                    update.columns(),
                    path
            );
        }
        if (analyzed instanceof AnalyzedDelete delete) {
            AccessPathChoice path = AccessPathChooser.choose(
                    delete.where().orElse(null),
                    delete.columns(),
                    delete.indexes()
            );
            return new DeletePlan(
                    delete.database(),
                    delete.table(),
                    delete.where().orElse(null),
                    delete.columns(),
                    path
            );
        }
        if (analyzed instanceof UnresolvedQuery unresolved) {
            return new UnresolvedPlan(unresolved);
        }
        throw new IllegalArgumentException("unsupported analyzed query: " + analyzed);
    }
}
