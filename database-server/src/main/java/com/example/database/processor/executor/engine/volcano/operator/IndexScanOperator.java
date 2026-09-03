package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.processor.planner.IndexScanSpec;
import com.example.database.storage.index.IndexRange;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.lock.LockMode;
import com.example.database.storage.page.Rid;
import com.example.database.storage.table.TableStore;

import java.util.Iterator;
import java.util.Objects;

/**
 * Index probe (equality or range): {@code lookupRange} yields heap addresses in key order,
 * then each row is loaded and optionally S-locked like {@link SeqScan}.
 */
public final class IndexScanOperator implements VolcanoOperator {

    private final IndexStore indexStore;
    private final TableStore tableStore;
    private final LockManager lockManager;
    private final String database;
    private final String table;
    private final IndexScanSpec scanSpec;
    private Iterator<Rid> ridIterator;
    private Tuple lockedTuple;

    public IndexScanOperator(
            IndexStore indexStore,
            TableStore tableStore,
            LockManager lockManager,
            String database,
            String table,
            IndexScanSpec scanSpec
    ) {
        this.indexStore = Objects.requireNonNull(indexStore, "indexStore");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.lockManager = lockManager;
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        this.scanSpec = Objects.requireNonNull(scanSpec, "scanSpec");
    }

    @Override
    public void open() {
        IndexRange range = new IndexRange(
                scanSpec.lowKey(),
                scanSpec.lowInclusive(),
                scanSpec.highKey(),
                scanSpec.highInclusive(),
                scanSpec.prefixColumns()
        );
        ridIterator = indexStore.lookupRange(database, table, scanSpec.indexName(), range);
    }

    @Override
    public Tuple next() {
        unlockCurrentRow();
        if (ridIterator == null) {
            return null;
        }
        while (ridIterator.hasNext()) {
            Rid rid = ridIterator.next();
            Tuple snapshotRow = tableStore.findByRid(database, table, rid).orElse(null);
            if (snapshotRow == null) {
                continue;
            }
            long rowId = snapshotRow.rowId();
            if (lockManager != null) {
                lockManager.lockRow(database, table, rowId, LockMode.S);
                Tuple current = tableStore.findByRowId(database, table, rowId).orElse(null);
                if (current == null) {
                    lockManager.unlockRow(database, table, rowId, LockMode.S);
                    continue;
                }
                lockedTuple = current;
                return current;
            }
            lockedTuple = snapshotRow;
            return snapshotRow;
        }
        return null;
    }

    @Override
    public void close() {
        unlockCurrentRow();
        ridIterator = null;
    }

    private void unlockCurrentRow() {
        if (lockedTuple != null && lockManager != null) {
            lockManager.unlockRow(database, table, lockedTuple.rowId(), LockMode.S);
            lockedTuple = null;
        } else if (lockedTuple != null) {
            lockedTuple = null;
        }
    }
}
