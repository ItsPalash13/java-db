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
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.table.TableStore;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DDL as a catalog state change, not a Volcano {@code next()} loop.
 * Table-scoped DDL takes table X (and database IX) before a short catalog lock for persist.
 */
public final class CommandExecutor implements QueryExecutor {

    private final CatalogManager catalogManager;
    private final TransactionManager transactionManager;
    private final LockManager lockManager;
    private final WALManager walManager;
    private final TableStore tableStore;

    public CommandExecutor(
            CatalogManager catalogManager,
            TransactionManager transactionManager,
            LockManager lockManager,
            WALManager walManager,
            TableStore tableStore
    ) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof CreateDatabasePlan) {
            return runCatalogOnly(plan);
        }
        if (plan instanceof DropDatabasePlan dropDatabase) {
            return runDropDatabase(dropDatabase);
        }
        if (isTableScoped(plan)) {
            return runTableScoped(plan);
        }
        throw new ExecutionException("CommandExecutor cannot execute " + plan.queryType());
    }

    private QueryResult runCatalogOnly(ExecutionPlan plan) {
        if (transactionManager.inExplicitTransaction()) {
            return executeUnderCatalogLock(plan);
        }
        return transactionManager.runInTransaction(
                () -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan))
        );
    }

    private QueryResult runDropDatabase(DropDatabasePlan plan) {
        if (transactionManager.inExplicitTransaction()) {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return lockManager.runWithDatabase(plan.database(), LockMode.X, () -> executeUnderCatalogLock(plan));
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return lockManager.runWithDatabase(
                        plan.database(),
                        LockMode.X,
                        () -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan))
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        });
    }

    private QueryResult runTableScoped(ExecutionPlan plan) {
        String database = tableDatabase(plan);
        String table = tableName(plan);
        if (transactionManager.inExplicitTransaction()) {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return lockManager.runWithTable(
                        database,
                        table,
                        LockMode.X,
                        () -> executeUnderCatalogLock(plan)
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return lockManager.runWithTable(
                        database,
                        table,
                        LockMode.X,
                        () -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan))
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        });
    }

    private static boolean isTableScoped(ExecutionPlan plan) {
        return plan instanceof CreateTablePlan
                || plan instanceof DropTablePlan
                || plan instanceof AddColumnPlan
                || plan instanceof DropColumnPlan
                || plan instanceof CreateIndexPlan
                || plan instanceof DropIndexPlan;
    }

    private static String tableDatabase(ExecutionPlan plan) {
        if (plan instanceof CreateTablePlan p) {
            return p.database();
        }
        if (plan instanceof DropTablePlan p) {
            return p.database();
        }
        if (plan instanceof AddColumnPlan p) {
            return p.database();
        }
        if (plan instanceof DropColumnPlan p) {
            return p.database();
        }
        if (plan instanceof CreateIndexPlan p) {
            return p.database();
        }
        if (plan instanceof DropIndexPlan p) {
            return p.database();
        }
        throw new IllegalArgumentException("not table scoped: " + plan);
    }

    private static String tableName(ExecutionPlan plan) {
        if (plan instanceof CreateTablePlan p) {
            return p.table();
        }
        if (plan instanceof DropTablePlan p) {
            return p.table();
        }
        if (plan instanceof AddColumnPlan p) {
            return p.table();
        }
        if (plan instanceof DropColumnPlan p) {
            return p.table();
        }
        if (plan instanceof CreateIndexPlan p) {
            return p.table();
        }
        if (plan instanceof DropIndexPlan p) {
            return p.table();
        }
        throw new IllegalArgumentException("not table scoped: " + plan);
    }

    private QueryResult executeUnderCatalogLock(ExecutionPlan plan) {
        int txnId = transactionManager.currentTxnId();
        try {
            if (plan instanceof CreateTablePlan createTable) {
                log(txnId, WalRecord.createTable(
                        txnId,
                        createTable.database(),
                        createTable.table(),
                        toPayloads(createTable.columns())
                ));
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
                log(txnId, WalRecord.dropTable(txnId, dropTable.database(), dropTable.table()));
                catalogManager.dropTable(dropTable.database(), dropTable.table());
                tableStore.dropTable(dropTable.database(), dropTable.table());
                return QueryResult.ok();
            }
            if (plan instanceof CreateDatabasePlan createDatabase) {
                log(txnId, WalRecord.createDatabase(txnId, createDatabase.database()));
                catalogManager.createDatabase(createDatabase.database());
                return QueryResult.ok();
            }
            if (plan instanceof DropDatabasePlan dropDatabase) {
                log(txnId, WalRecord.dropDatabase(txnId, dropDatabase.database()));
                catalogManager.dropDatabase(dropDatabase.database());
                tableStore.dropDatabase(dropDatabase.database());
                return QueryResult.ok();
            }
            if (plan instanceof AddColumnPlan addColumn) {
                ColumnMetadata column = addColumn.column();
                log(txnId, WalRecord.addColumn(
                        txnId,
                        addColumn.database(),
                        addColumn.table(),
                        new WalRecord.ColumnPayload(column.name(), column.type(), column.nullable())
                ));
                catalogManager.addColumn(addColumn.database(), addColumn.table(), column);
                return QueryResult.ok();
            }
            if (plan instanceof DropColumnPlan dropColumn) {
                log(txnId, WalRecord.dropColumn(
                        txnId,
                        dropColumn.database(),
                        dropColumn.table(),
                        dropColumn.column()
                ));
                catalogManager.dropColumn(
                        dropColumn.database(),
                        dropColumn.table(),
                        dropColumn.column()
                );
                return QueryResult.ok();
            }
            if (plan instanceof CreateIndexPlan createIndex) {
                log(txnId, WalRecord.createIndex(
                        txnId,
                        createIndex.database(),
                        createIndex.table(),
                        createIndex.index(),
                        createIndex.columnIds()
                ));
                catalogManager.createIndex(
                        createIndex.database(),
                        createIndex.table(),
                        IndexMetadata.define(createIndex.index(), createIndex.columnIds())
                );
                return QueryResult.ok();
            }
            if (plan instanceof DropIndexPlan dropIndex) {
                log(txnId, WalRecord.dropIndex(txnId, dropIndex.index()));
                catalogManager.dropIndex(dropIndex.index());
                return QueryResult.ok();
            }
        } catch (CatalogException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        throw new ExecutionException("CommandExecutor cannot execute " + plan.queryType());
    }

    private void log(int txnId, WalRecord record) {
        walManager.append(record.withTxnId(txnId));
    }

    private static List<WalRecord.ColumnPayload> toPayloads(List<ColumnMetadata> columns) {
        List<WalRecord.ColumnPayload> payloads = new ArrayList<>(columns.size());
        for (ColumnMetadata column : columns) {
            payloads.add(new WalRecord.ColumnPayload(column.name(), column.type(), column.nullable()));
        }
        return payloads;
    }
}
