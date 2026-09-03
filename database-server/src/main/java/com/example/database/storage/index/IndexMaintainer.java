package com.example.database.storage.index;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.page.Rid;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;

import java.util.Arrays;
import java.util.Objects;

/**
 * Keeps secondary indexes aligned with heap DML. Appends logical {@code INDEX_INSERT}/
 * {@code INDEX_DELETE} to {@code wal.log} for crash redo. Physical {@code .idx} durability
 * still uses {@link IndexPageWal} on flush.
 * <p>
 * Does <em>not</em> record Index* undo entries: heap undo ({@code restoreRow}/{@code update}/
 * {@code delete}) re-runs this maintainer with the post-undo RID. Recording Index* undo with
 * the pre-delete RID would fight {@code restoreRow}'s new slot and break unique keys / lookups.
 */
public final class IndexMaintainer {

    private final CatalogManager catalogManager;
    private final IndexStore indexStore;
    private final TransactionManager transactionManager;
    private final WALManager walManager;

    public IndexMaintainer(
            CatalogManager catalogManager,
            IndexStore indexStore,
            TransactionManager transactionManager
    ) {
        this(catalogManager, indexStore, transactionManager, null);
    }

    public IndexMaintainer(
            CatalogManager catalogManager,
            IndexStore indexStore,
            TransactionManager transactionManager,
            WALManager walManager
    ) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
        this.indexStore = Objects.requireNonNull(indexStore, "indexStore");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.walManager = walManager;
    }

    public void onInsert(String database, String table, Object[] values, Rid rid) {
        TableMetadata metadata = tableMetadata(database, table);
        for (IndexMetadata index : metadata.indexes()) {
            Object[] key = IndexKeySupport.keyValues(metadata, index, values);
            logIndexInsert(database, table, index.name(), key, rid);
            indexStore.insert(database, table, index.name(), key, rid);
        }
    }

    public void onDelete(String database, String table, Object[] values, Rid rid) {
        TableMetadata metadata = tableMetadata(database, table);
        for (IndexMetadata index : metadata.indexes()) {
            Object[] key = IndexKeySupport.keyValues(metadata, index, values);
            logIndexDelete(database, table, index.name(), key, rid);
            indexStore.delete(database, table, index.name(), key, rid);
        }
    }

    public void onUpdate(
            String database,
            String table,
            Object[] oldValues,
            Object[] newValues,
            Rid oldRid,
            Rid newRid
    ) {
        TableMetadata metadata = tableMetadata(database, table);
        for (IndexMetadata index : metadata.indexes()) {
            Object[] oldKey = IndexKeySupport.keyValues(metadata, index, oldValues);
            Object[] newKey = IndexKeySupport.keyValues(metadata, index, newValues);
            if (oldRid.equals(newRid) && Arrays.equals(oldKey, newKey)) {
                continue;
            }
            logIndexDelete(database, table, index.name(), oldKey, oldRid);
            indexStore.delete(database, table, index.name(), oldKey, oldRid);
            logIndexInsert(database, table, index.name(), newKey, newRid);
            indexStore.insert(database, table, index.name(), newKey, newRid);
        }
    }

    private TableMetadata tableMetadata(String database, String table) {
        return catalogManager.getTable(database, table).orElseThrow(
                () -> new IllegalStateException("table not in catalog: " + database + "." + table)
        );
    }

    private void logIndexInsert(String database, String table, String indexName, Object[] key, Rid rid) {
        if (walManager == null || !transactionManager.active()) {
            return;
        }
        // Logical index redo; LSN not stamped on .idx pages (header reused for tree links).
        walManager.appendReturningLsn(
                WalRecord.indexInsert(transactionManager.currentTxnId(), database, table, indexName, key, rid)
        );
    }

    private void logIndexDelete(String database, String table, String indexName, Object[] key, Rid rid) {
        if (walManager == null || !transactionManager.active()) {
            return;
        }
        walManager.appendReturningLsn(
                WalRecord.indexDelete(transactionManager.currentTxnId(), database, table, indexName, key, rid)
        );
    }
}
