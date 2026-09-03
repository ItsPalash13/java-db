package com.example.database.storage.undo;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.page.Rid;
import com.example.database.storage.table.TableStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-txn append-only undo log in memory. Sufficient for heap rollback before WAL DML exists.
 * Index changes are inverted by re-running {@code IndexMaintainer} from heap undo
 * ({@code restoreRow}/{@code update}/{@code delete}), not by Index* undo records.
 */
public final class DefaultUndoManager implements UndoManager {

    private final IndexStore indexStore;
    private final Map<Integer, List<UndoRecord>> recordsByTxn = new HashMap<>();

    public DefaultUndoManager() {
        this(null);
    }

    public DefaultUndoManager(IndexStore indexStore) {
        this.indexStore = indexStore != null ? indexStore : noopIndexStore();
    }

    @Override
    public void recordInsert(int txnId, String database, String table, long rowId) {
        append(txnId, new UndoRecord.DeleteInsert(database, table, rowId));
    }

    @Override
    public void recordUpdate(int txnId, String database, String table, long rowId, Object[] beforeValues) {
        append(txnId, new UndoRecord.RestoreUpdate(database, table, rowId, beforeValues.clone()));
    }

    @Override
    public void recordDelete(int txnId, String database, String table, long rowId, Object[] beforeValues) {
        append(txnId, new UndoRecord.RestoreDelete(database, table, rowId, beforeValues.clone()));
    }

    @Override
    public void recordIndexInsert(int txnId, String database, String table, String indexName, Object[] key, Rid rid) {
        append(txnId, new UndoRecord.IndexInsert(database, table, indexName, key.clone(), rid));
    }

    @Override
    public void recordIndexDelete(int txnId, String database, String table, String indexName, Object[] key, Rid rid) {
        append(txnId, new UndoRecord.IndexDelete(database, table, indexName, key.clone(), rid));
    }

    @Override
    public void rollback(int txnId, TableStore tableStore) {
        Objects.requireNonNull(tableStore, "tableStore");
        TableStore target = unwrapDelegate(tableStore);
        List<UndoRecord> records = removeRecords(txnId);
        for (int i = records.size() - 1; i >= 0; i--) {
            applyUndo(records.get(i), target, indexStore);
        }
    }

    /** Undo must hit the raw heap — not {@link com.example.database.storage.table.UndoableTableStore}. */
    private static TableStore unwrapDelegate(TableStore tableStore) {
        if (tableStore instanceof com.example.database.storage.table.UndoableTableStore undoable) {
            return undoable.delegate();
        }
        return tableStore;
    }

    @Override
    public void clear(int txnId) {
        removeRecords(txnId);
    }

    private void append(int txnId, UndoRecord record) {
        recordsByTxn.computeIfAbsent(txnId, ignored -> new ArrayList<>()).add(record);
    }

    private List<UndoRecord> removeRecords(int txnId) {
        List<UndoRecord> records = recordsByTxn.remove(txnId);
        if (records == null) {
            return List.of();
        }
        return records;
    }

    private static void applyUndo(UndoRecord record, TableStore tableStore, IndexStore indexStore) {
        if (record instanceof UndoRecord.DeleteInsert deleteInsert) {
            tableStore.delete(deleteInsert.database(), deleteInsert.table(), deleteInsert.rowId());
            return;
        }
        if (record instanceof UndoRecord.RestoreUpdate restoreUpdate) {
            tableStore.update(
                    restoreUpdate.database(),
                    restoreUpdate.table(),
                    restoreUpdate.rowId(),
                    restoreUpdate.beforeValues()
            );
            return;
        }
        if (record instanceof UndoRecord.RestoreDelete restoreDelete) {
            tableStore.restoreRow(
                    restoreDelete.database(),
                    restoreDelete.table(),
                    new Tuple(restoreDelete.rowId(), restoreDelete.values())
            );
            return;
        }
        if (record instanceof UndoRecord.IndexInsert indexInsert) {
            indexStore.delete(
                    indexInsert.database(),
                    indexInsert.table(),
                    indexInsert.indexName(),
                    indexInsert.key(),
                    indexInsert.rid()
            );
            return;
        }
        if (record instanceof UndoRecord.IndexDelete indexDelete) {
            indexStore.insert(
                    indexDelete.database(),
                    indexDelete.table(),
                    indexDelete.indexName(),
                    indexDelete.key(),
                    indexDelete.rid()
            );
        }
    }

    private static IndexStore noopIndexStore() {
        return new IndexStore() {
            @Override
            public void createIndex(String database, String table, com.example.database.storage.catalog.IndexMetadata index, com.example.database.storage.catalog.ColumnType[] keyTypes) {
            }

            @Override
            public void dropIndex(String database, String table, String indexName) {
            }

            @Override
            public void dropTableIndexes(String database, String table, java.util.List<com.example.database.storage.catalog.IndexMetadata> indexes) {
            }

            @Override
            public void insert(String database, String table, String indexName, Object[] key, Rid rid) {
            }

            @Override
            public void delete(String database, String table, String indexName, Object[] key, Rid rid) {
            }

            @Override
            public java.util.Iterator<Rid> lookupEquals(String database, String table, String indexName, Object[] key) {
                return java.util.List.<Rid>of().iterator();
            }

            @Override
            public java.util.Iterator<Rid> lookupRange(
                    String database,
                    String table,
                    String indexName,
                    com.example.database.storage.index.IndexRange range
            ) {
                return java.util.List.<Rid>of().iterator();
            }
        };
    }
}
