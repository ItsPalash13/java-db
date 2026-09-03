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
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.index.IndexBuilder;
import com.example.database.storage.index.IndexKeySupport;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.index.IndexStoreException;
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
 * Takes ENGINE IX first so CHECKPOINT (ENGINE X) can quiesce DDL, then table X
 * (and database IX) before a short catalog lock for persist.
 */
public final class CommandExecutor implements QueryExecutor {

    private final CatalogManager catalogManager;
    private final TransactionManager transactionManager;
    private final LockManager lockManager;
    private final WALManager walManager;
    private final TableStore tableStore;
    private final IndexStore indexStore;

    public CommandExecutor(
            CatalogManager catalogManager,
            TransactionManager transactionManager,
            LockManager lockManager,
            WALManager walManager,
            TableStore tableStore,
            IndexStore indexStore
    ) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.walManager = Objects.requireNonNull(walManager, "walManager");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.indexStore = Objects.requireNonNull(indexStore, "indexStore");
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
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return withEngineIx(() -> executeUnderCatalogLock(plan));
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return withEngineIx(() -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan)));
            } finally {
                lockManager.clearOwnerBinding();
            }
        });
    }

    private QueryResult runDropDatabase(DropDatabasePlan plan) {
        if (transactionManager.inExplicitTransaction()) {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return withEngineIx(() ->
                        lockManager.runWithDatabase(plan.database(), LockMode.X, () -> executeUnderCatalogLock(plan))
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return withEngineIx(() ->
                        lockManager.runWithDatabase(
                                plan.database(),
                                LockMode.X,
                                () -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan))
                        )
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
                return withEngineIx(() ->
                        lockManager.runWithTable(
                                database,
                                table,
                                LockMode.X,
                                () -> executeUnderCatalogLock(plan)
                        )
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        }
        return transactionManager.runInTransaction(() -> {
            lockManager.bindOwner(transactionManager.currentTxnId());
            try {
                return withEngineIx(() ->
                        lockManager.runWithTable(
                                database,
                                table,
                                LockMode.X,
                                () -> lockManager.runExclusiveCatalog(() -> executeUnderCatalogLock(plan))
                        )
                );
            } finally {
                lockManager.clearOwnerBinding();
            }
        });
    }

    /**
     * ENGINE IX before any db/table/catalog lock so CHECKPOINT ENGINE X queues DDL.
     * Caller must already {@link LockManager#bindOwner}; released in finally for statement scope.
     */
    private QueryResult withEngineIx(java.util.function.Supplier<QueryResult> action) {
        lockManager.lockEngine(LockMode.IX);
        try {
            return action.get();
        } finally {
            lockManager.unlockEngine(LockMode.IX);
        }
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
                TableMetadata created = catalogManager.createTable(
                        TableMetadata.define(
                                createTable.database(),
                                createTable.table(),
                                createTable.columns(),
                                createTable.primaryKeyColumn()
                        )
                );
                tableStore.prepareTable(createTable.database(), createTable.table());
                // Auto-create a unique index for the PRIMARY KEY column when declared.
                if (createTable.primaryKeyColumn().isPresent()) {
                    String pkCol = createTable.primaryKeyColumn().get();
                    int pkColumnId = -1;
                    for (ColumnMetadata col : created.columns()) {
                        if (col.name().equals(pkCol)) {
                            pkColumnId = col.columnId().orElseThrow();
                            break;
                        }
                    }
                    String pkIndexName = "pk_" + createTable.table() + "_" + pkCol;
                    List<Integer> pkColumnIds = List.of(pkColumnId);
                    IndexMetadata pkIndex = new IndexMetadata(pkIndexName, pkColumnIds, true);
                    ColumnType[] keyTypes = IndexKeySupport.keyTypes(created, pkColumnIds);
                    catalogManager.createIndex(createTable.database(), createTable.table(), pkIndex);
                    indexStore.createIndex(createTable.database(), createTable.table(), pkIndex, keyTypes);
                    // No bulk build needed — table is empty at this point.
                }
                return QueryResult.ok();
            }
            if (plan instanceof DropTablePlan dropTable) {
                log(txnId, WalRecord.dropTable(txnId, dropTable.database(), dropTable.table()));
                TableMetadata tableMetadata = catalogManager.getTable(dropTable.database(), dropTable.table()).orElseThrow();
                indexStore.dropTableIndexes(dropTable.database(), dropTable.table(), tableMetadata.indexes());
                tableStore.dropTable(dropTable.database(), dropTable.table());
                catalogManager.dropTable(dropTable.database(), dropTable.table());
                return QueryResult.ok();
            }
            if (plan instanceof CreateDatabasePlan createDatabase) {
                log(txnId, WalRecord.createDatabase(txnId, createDatabase.database()));
                catalogManager.createDatabase(createDatabase.database());
                return QueryResult.ok();
            }
            if (plan instanceof DropDatabasePlan dropDatabase) {
                log(txnId, WalRecord.dropDatabase(txnId, dropDatabase.database()));
                tableStore.dropDatabase(dropDatabase.database());
                catalogManager.dropDatabase(dropDatabase.database());
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
            if (plan instanceof CreateIndexPlan indexPlan) {
                String database = indexPlan.database();
                String table = indexPlan.table();
                String indexName = indexPlan.index();
                List<Integer> columnIds = indexPlan.columnIds();
                boolean unique = indexPlan.unique();
                log(txnId, WalRecord.createIndex(txnId, database, table, indexName, columnIds));
                IndexMetadata indexMetadata = new IndexMetadata(indexName, columnIds, unique);
                TableMetadata tableMetadata = catalogManager.getTable(database, table).orElseThrow();
                ColumnType[] keyTypes = IndexKeySupport.keyTypes(tableMetadata, columnIds);
                catalogManager.createIndex(database, table, indexMetadata);
                try {
                    indexStore.createIndex(database, table, indexMetadata, keyTypes);
                    IndexBuilder.bulkBuild(tableStore, indexStore, database, table, indexMetadata, tableMetadata);
                } catch (RuntimeException e) {
                    // Catalog row must not outlive a failed .idx create (ROLLBACK also drops files).
                    try {
                        catalogManager.dropIndex(indexName);
                    } catch (RuntimeException ignored) {
                        // best-effort
                    }
                    try {
                        indexStore.dropIndex(database, table, indexName);
                    } catch (RuntimeException ignored) {
                        // best-effort
                    }
                    throw e;
                }
                return QueryResult.ok();
            }
            if (plan instanceof DropIndexPlan dropIndex) {
                log(txnId, WalRecord.dropIndex(txnId, dropIndex.index()));
                indexStore.dropIndex(dropIndex.database(), dropIndex.table(), dropIndex.index());
                catalogManager.dropIndex(dropIndex.index());
                return QueryResult.ok();
            }
        } catch (CatalogException e) {
            throw new ExecutionException(e.getMessage(), e);
        } catch (IndexStoreException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        Objects.requireNonNull(plan, "plan");
        throw new ExecutionException("CommandExecutor cannot execute " + plan.queryType());
    }

    private void log(int txnId, WalRecord record) {
        walManager.append(Objects.requireNonNull(record, "record").withTxnId(txnId));
    }

    private static List<WalRecord.ColumnPayload> toPayloads(List<ColumnMetadata> columns) {
        List<WalRecord.ColumnPayload> payloads = new ArrayList<>(columns.size());
        for (ColumnMetadata column : columns) {
            payloads.add(new WalRecord.ColumnPayload(column.name(), column.type(), column.nullable()));
        }
        return payloads;
    }
}
