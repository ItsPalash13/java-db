package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.bufferpool.BufferFrame;
import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.bufferpool.PageId;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.index.IndexMaintainer;
import com.example.database.storage.page.HeapMetaPage;
import com.example.database.storage.page.HeapPage;
import com.example.database.storage.page.InMemoryRidMap;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageLayoutException;
import com.example.database.storage.page.PageType;
import com.example.database.storage.page.Rid;
import com.example.database.storage.page.RidMap;
import com.example.database.storage.page.RowCodec;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Page-backed heap store: one {@code .ibd} per table, rows in slotted {@link HeapPage}s
 * through {@link BufferPool}. Page 0 is {@link HeapMetaPage} (stamps {@code PAGE_SIZE});
 * row pages start at page 1. Logical {@code rowId} maps to {@link Rid} in RAM
 * ({@link RidMap}); restart rebuilds the map by scanning live slots.
 * <p>
 * Phase 6: on DML, appends logical WAL then stamps page LSN before {@code markDirty}.
 * Undo {@link #restoreRow} does not log (ROLLBACK / redo use this path). Index maintenance
 * on {@code restoreRow} is skipped when {@link #setSuppressSideEffects} is true — undo and
 * DML redo restore indexes via Index* undo / {@code INDEX_*} WAL, not via {@link IndexMaintainer}.
 *
 * <pre>
 *   // Wired by DefaultStorageEngine under UndoableTableStore.
 *   Tuple t = store.insert("shop", "users", new Object[]{1, "Ada"});
 *   store.findByRowId("shop", "users", t.rowId());
 * </pre>
 */
public final class FileTableStore implements TableStore {

    private final CatalogManager catalogManager;
    private final BufferPool bufferPool;
    private final PhysicalStorage physicalStorage;
    private final IndexMaintainer indexMaintainer;
    private final com.example.database.storage.wal.WALManager walManager;
    private final com.example.database.storage.transaction.TransactionManager transactionManager;
    private final int pageSize;
    private final Map<String, HeapState> heaps = new ConcurrentHashMap<>();
    // When true: no WAL append and no IndexMaintainer (startup redo / internal restore).
    private volatile boolean suppressSideEffects;

    public FileTableStore(
            CatalogManager catalogManager,
            BufferPool bufferPool,
            PhysicalStorage physicalStorage
    ) {
        this(catalogManager, bufferPool, physicalStorage, null, null, null);
    }

    public FileTableStore(
            CatalogManager catalogManager,
            BufferPool bufferPool,
            PhysicalStorage physicalStorage,
            IndexMaintainer indexMaintainer
    ) {
        this(catalogManager, bufferPool, physicalStorage, indexMaintainer, null, null);
    }

    public FileTableStore(
            CatalogManager catalogManager,
            BufferPool bufferPool,
            PhysicalStorage physicalStorage,
            IndexMaintainer indexMaintainer,
            com.example.database.storage.wal.WALManager walManager,
            com.example.database.storage.transaction.TransactionManager transactionManager
    ) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
        this.indexMaintainer = indexMaintainer;
        this.walManager = walManager;
        this.transactionManager = transactionManager;
        this.pageSize = physicalStorage.pageSize();
    }

    /**
     * Suppress WAL + index maintenance (startup DML redo applies indexes separately).
     */
    public void setSuppressSideEffects(boolean suppress) {
        this.suppressSideEffects = suppress;
    }

    @Override
    public void prepareTable(String database, String table) {
        HeapState state = heapState(database, table);
        String file = ibdFile(database, table);
        synchronized (state) {
            if (!physicalStorage.exists(file)) {
                physicalStorage.create(file);
            }
            ensureHeapMeta(file);
            state.scannedFromDisk = true;
        }
    }

    @Override
    public Tuple insert(String database, String table, Object[] values) {
        Objects.requireNonNull(values, "values");
        // PRIMARY KEY columns must not be null.
        rejectNullPrimaryKey(database, table, values);
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            ensureFile(database, table, state);
            long rowId = state.nextRowId++;
            long lsn = logInsert(database, table, rowId, values);
            Rid rid = writeRow(database, table, state, rowId, values, types, lsn);
            state.ridMap.put(rowId, rid);
            if (!suppressSideEffects && indexMaintainer != null) {
                try {
                    indexMaintainer.onInsert(database, table, values, rid);
                } catch (RuntimeException e) {
                    // Unique (or other) index failure after heap write must not leave an orphan
                    // row: UndoableTableStore only records undo after a successful insert return,
                    // so abort would not roll this row back without a compensating delete.
                    deleteSlot(database, table, rid, 0L);
                    state.ridMap.remove(rowId);
                    throw e;
                }
            }
            return new Tuple(rowId, values);
        }
    }

    @Override
    public Iterator<Tuple> scan(String database, String table) {
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            if (!physicalStorage.exists(ibdFile(database, table))) {
                return List.<Tuple>of().iterator();
            }
            return List.copyOf(collectLiveRows(database, table, types)).iterator();
        }
    }

    @Override
    public void update(String database, String table, long rowId, Object[] values) {
        Objects.requireNonNull(values, "values");
        // PRIMARY KEY columns must not be null.
        rejectNullPrimaryKey(database, table, values);
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            Optional<Rid> rid = state.ridMap.get(rowId);
            if (rid.isEmpty()) {
                return;
            }
            Optional<Tuple> before = readAtRid(database, table, rid.get(), types);
            if (before.isEmpty()) {
                return;
            }
            Object[] oldValues = before.get().values();
            Rid oldRid = rid.get();
            long lsn = logUpdate(database, table, rowId, values);
            if (tryUpdateInPlace(database, table, oldRid, rowId, values, types, lsn)) {
                if (!suppressSideEffects && indexMaintainer != null) {
                    indexMaintainer.onUpdate(database, table, oldValues, values, oldRid, oldRid);
                }
                return;
            }
            deleteSlot(database, table, oldRid, lsn);
            Rid newRid = writeRow(database, table, state, rowId, values, types, lsn);
            state.ridMap.put(rowId, newRid);
            if (!suppressSideEffects && indexMaintainer != null) {
                indexMaintainer.onUpdate(database, table, oldValues, values, oldRid, newRid);
            }
        }
    }

    @Override
    public void delete(String database, String table, long rowId) {
        HeapState state = heapState(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            Optional<Rid> rid = state.ridMap.get(rowId);
            if (rid.isEmpty()) {
                return;
            }
            ColumnType[] types = columnTypes(database, table);
            Optional<Tuple> before = readAtRid(database, table, rid.get(), types);
            long lsn = logDelete(database, table, rowId);
            if (before.isPresent() && !suppressSideEffects && indexMaintainer != null) {
                indexMaintainer.onDelete(database, table, before.get().values(), rid.get());
            }
            deleteSlot(database, table, rid.get(), lsn);
            state.ridMap.remove(rowId);
        }
    }

    @Override
    public void dropTable(String database, String table) {
        String key = TableHeapFiles.tableKey(database, table);
        HeapState removed = heaps.remove(key);
        String file = ibdFile(database, table);
        if (physicalStorage.exists(file)) {
            // Drop any dirty frames for this file before unlinking the path.
            bufferPool.flushAll();
            physicalStorage.delete(file);
        }
        if (removed != null) {
            synchronized (removed) {
                removed.ridMap.clear();
                removed.nextRowId = 1;
                removed.lastPageId = -1;
                removed.scannedFromDisk = false;
            }
        }
    }

    @Override
    public void dropDatabase(String database) {
        Objects.requireNonNull(database, "database");
        for (TableMetadata table : catalogManager.allTables()) {
            if (table.database().equals(database)) {
                dropTable(database, table.name());
            }
        }
    }

    @Override
    public TableSnapshot snapshot() {
        // Explicit txn rollback uses UndoManager, not heap snapshots.
        return new TableSnapshot(Map.of(), 1L);
    }

    @Override
    public void restoreSnapshot(TableSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        // no-op — file heap relies on undo records
    }

    @Override
    public Optional<Rid> findRid(String database, String table, long rowId) {
        HeapState state = heapState(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            return state.ridMap.get(rowId);
        }
    }

    @Override
    public Optional<Tuple> findByRid(String database, String table, Rid rid) {
        Objects.requireNonNull(rid, "rid");
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            return readAtRid(database, table, rid, types);
        }
    }

    @Override
    public Optional<Tuple> findByRowId(String database, String table, long rowId) {
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        synchronized (state) {
            ensureLoaded(database, table, state);
            Optional<Rid> rid = state.ridMap.get(rowId);
            if (rid.isEmpty()) {
                return Optional.empty();
            }
            return readAtRid(database, table, rid.get(), types);
        }
    }

    @Override
    public void restoreRow(String database, String table, Tuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        HeapState state = heapState(database, table);
        ColumnType[] types = columnTypes(database, table);
        Object[] values = tuple.values().clone();
        synchronized (state) {
            ensureLoaded(database, table, state);
            ensureFile(database, table, state);
            Optional<Rid> existing = state.ridMap.get(tuple.rowId());
            if (existing.isPresent()) {
                if (tryUpdateInPlace(database, table, existing.get(), tuple.rowId(), values, types, 0L)) {
                    state.nextRowId = Math.max(state.nextRowId, tuple.rowId() + 1);
                    return;
                }
                deleteSlot(database, table, existing.get(), 0L);
            }
            Rid rid = writeRow(database, table, state, tuple.rowId(), values, types, 0L);
            state.ridMap.put(tuple.rowId(), rid);
            state.nextRowId = Math.max(state.nextRowId, tuple.rowId() + 1);
            // Indexes for crash redo come from WAL INDEX_*; undo of DELETE re-runs IndexMaintainer
            // here with the new RID (Index* undo would re-insert the stale pre-delete RID).
            if (!suppressSideEffects && indexMaintainer != null) {
                indexMaintainer.onInsert(database, table, values, rid);
            }
        }
    }

    private HeapState heapState(String database, String table) {
        return heaps.computeIfAbsent(
                TableHeapFiles.tableKey(database, table),
                ignored -> new HeapState()
        );
    }

    private void ensureFile(String database, String table, HeapState state) {
        String file = ibdFile(database, table);
        if (!physicalStorage.exists(file)) {
            physicalStorage.create(file);
            state.scannedFromDisk = true;
        }
        ensureHeapMeta(file);
    }

    /**
     * Page 0 must be {@link HeapMetaPage} with stamped {@code PAGE_SIZE}.
     * Creates it when the {@code .ibd} is still empty.
     */
    private void ensureHeapMeta(String file) {
        long length = physicalStorage.byteLength(file);
        if (length == 0) {
            BufferFrame frame = bufferPool.newPage(file, PageType.HEAP_META);
            try {
                bufferPool.latchExclusive(frame);
                try {
                    // createEmpty already stamped pageSize; wrap confirms type.
                    HeapMetaPage.wrap(frame.data());
                    bufferPool.markDirty(frame);
                } finally {
                    bufferPool.unlatch(frame);
                }
            } finally {
                bufferPool.unpin(frame);
            }
            return;
        }
        if (length % pageSize != 0) {
            throw new PhysicalStorageException(
                    "heap file " + file + " length " + length
                            + " is not a multiple of PAGE_SIZE " + pageSize
            );
        }
        BufferFrame frame = bufferPool.pin(new PageId(file, 0));
        try {
            bufferPool.latchShared(frame);
            try {
                HeapMetaPage meta = HeapMetaPage.wrap(frame.data());
                int stamped = meta.pageSize();
                if (stamped != pageSize) {
                    throw new PhysicalStorageException(
                            "heap file " + file + " stamped PAGE_SIZE " + stamped
                                    + " does not match server PAGE_SIZE " + pageSize
                    );
                }
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private void ensureLoaded(String database, String table, HeapState state) {
        if (state.scannedFromDisk) {
            return;
        }
        String file = ibdFile(database, table);
        if (!physicalStorage.exists(file)) {
            state.scannedFromDisk = true;
            return;
        }
        ensureHeapMeta(file);
        ColumnType[] types = columnTypes(database, table);
        long fileLength = physicalStorage.byteLength(file);
        int pageCount = (int) (fileLength / pageSize);
        long maxRowId = 0;
        // Page 0 is HEAP_META — scan only HEAP data pages.
        for (int pageId = 1; pageId < pageCount; pageId++) {
            PageId pageKey = new PageId(file, pageId);
            BufferFrame frame = bufferPool.pin(pageKey);
            try {
                bufferPool.latchShared(frame);
                try {
                    HeapPage page = HeapPage.wrap(frame.data());
                    int slots = page.slotCount();
                    for (int slotId = 0; slotId < slots; slotId++) {
                        if (!page.isLive(slotId)) {
                            continue;
                        }
                        Tuple row = page.readPadded(slotId, types).orElseThrow();
                        state.ridMap.put(row.rowId(), new Rid(pageId, slotId));
                        maxRowId = Math.max(maxRowId, row.rowId());
                    }
                } finally {
                    bufferPool.unlatch(frame);
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        state.nextRowId = maxRowId + 1;
        state.lastPageId = pageCount > 1 ? pageCount - 1 : -1;
        state.scannedFromDisk = true;
    }

    private List<Tuple> collectLiveRows(String database, String table, ColumnType[] types) {
        String file = ibdFile(database, table);
        long fileLength = physicalStorage.byteLength(file);
        int pageCount = (int) (fileLength / pageSize);
        List<Tuple> rows = new ArrayList<>();
        for (int pageId = 1; pageId < pageCount; pageId++) {
            PageId pageKey = new PageId(file, pageId);
            BufferFrame frame = bufferPool.pin(pageKey);
            try {
                bufferPool.latchShared(frame);
                try {
                    HeapPage page = HeapPage.wrap(frame.data());
                    int slots = page.slotCount();
                    for (int slotId = 0; slotId < slots; slotId++) {
                        if (page.isLive(slotId)) {
                            page.readPadded(slotId, types).ifPresent(rows::add);
                        }
                    }
                } finally {
                    bufferPool.unlatch(frame);
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        return rows;
    }

    private long logInsert(String database, String table, long rowId, Object[] values) {
        if (suppressSideEffects || walManager == null || transactionManager == null || !transactionManager.active()) {
            return 0L;
        }
        return walManager.appendReturningLsn(
                com.example.database.storage.wal.WalRecord.insertRow(
                        transactionManager.currentTxnId(), database, table, rowId, values
                )
        );
    }

    private long logUpdate(String database, String table, long rowId, Object[] values) {
        if (suppressSideEffects || walManager == null || transactionManager == null || !transactionManager.active()) {
            return 0L;
        }
        return walManager.appendReturningLsn(
                com.example.database.storage.wal.WalRecord.updateRow(
                        transactionManager.currentTxnId(), database, table, rowId, values
                )
        );
    }

    private long logDelete(String database, String table, long rowId) {
        if (suppressSideEffects || walManager == null || transactionManager == null || !transactionManager.active()) {
            return 0L;
        }
        return walManager.appendReturningLsn(
                com.example.database.storage.wal.WalRecord.deleteRow(
                        transactionManager.currentTxnId(), database, table, rowId
                )
        );
    }

    private Rid writeRow(
            String database,
            String table,
            HeapState state,
            long rowId,
            Object[] values,
            ColumnType[] types,
            long lsn
    ) {
        if (state.lastPageId >= 0) {
            Optional<Rid> onLast = tryInsertOnPage(database, table, state.lastPageId, rowId, values, types, lsn);
            if (onLast.isPresent()) {
                return onLast.get();
            }
        }
        String file = ibdFile(database, table);
        BufferFrame frame = bufferPool.newPage(file);
        try {
            bufferPool.latchExclusive(frame);
            try {
                HeapPage page = HeapPage.wrap(frame.data());
                int slotId = page.insert(rowId, values, types);
                if (lsn > 0) {
                    page.setPageLsn(lsn);
                }
                bufferPool.markDirty(frame);
                state.lastPageId = frame.pageId().pageId();
                return new Rid(state.lastPageId, slotId);
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private Optional<Rid> tryInsertOnPage(
            String database,
            String table,
            int pageId,
            long rowId,
            Object[] values,
            ColumnType[] types,
            long lsn
    ) {
        String file = ibdFile(database, table);
        PageId pageKey = new PageId(file, pageId);
        BufferFrame frame = bufferPool.pin(pageKey);
        try {
            bufferPool.latchExclusive(frame);
            try {
                HeapPage page = HeapPage.wrap(frame.data());
                int need = PageLayout.SLOT_SIZE + RowCodec.encodedLength(rowId, values, types);
                if (page.freeSpace() < need) {
                    return Optional.empty();
                }
                int slotId = page.insert(rowId, values, types);
                if (lsn > 0) {
                    page.setPageLsn(lsn);
                }
                bufferPool.markDirty(frame);
                return Optional.of(new Rid(pageId, slotId));
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private boolean tryUpdateInPlace(
            String database,
            String table,
            Rid rid,
            long rowId,
            Object[] values,
            ColumnType[] types,
            long lsn
    ) {
        String file = ibdFile(database, table);
        PageId pageKey = new PageId(file, rid.pageId());
        BufferFrame frame = bufferPool.pin(pageKey);
        try {
            bufferPool.latchExclusive(frame);
            try {
                HeapPage page = HeapPage.wrap(frame.data());
                if (!page.isLive(rid.slotId())) {
                    return false;
                }
                int oldLength = encodedLengthAtSlot(page, rid.slotId());
                int newLength = RowCodec.encodedLength(rowId, values, types);
                if (newLength > oldLength) {
                    return false;
                }
                page.update(rid.slotId(), rowId, values, types);
                if (lsn > 0) {
                    page.setPageLsn(lsn);
                }
                bufferPool.markDirty(frame);
                return true;
            } catch (PageLayoutException e) {
                return false;
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private void deleteSlot(String database, String table, Rid rid, long lsn) {
        String file = ibdFile(database, table);
        PageId pageKey = new PageId(file, rid.pageId());
        BufferFrame frame = bufferPool.pin(pageKey);
        try {
            bufferPool.latchExclusive(frame);
            try {
                HeapPage page = HeapPage.wrap(frame.data());
                page.delete(rid.slotId());
                if (lsn > 0) {
                    page.setPageLsn(lsn);
                }
                bufferPool.markDirty(frame);
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private Optional<Tuple> readAtRid(String database, String table, Rid rid, ColumnType[] types) {
        String file = ibdFile(database, table);
        PageId pageKey = new PageId(file, rid.pageId());
        BufferFrame frame = bufferPool.pin(pageKey);
        try {
            bufferPool.latchShared(frame);
            try {
                HeapPage page = HeapPage.wrap(frame.data());
                return page.readPadded(rid.slotId(), types);
            } finally {
                bufferPool.unlatch(frame);
            }
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private int encodedLengthAtSlot(HeapPage page, int slotId) {
        byte[] data = page.data();
        int dir = PageLayout.HEADER_SIZE + slotId * PageLayout.SLOT_SIZE;
        return java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .getShort(dir + 2) & 0xFFFF;
    }

    /**
     * Rejects null values for PRIMARY KEY columns. Called before insert/update to enforce
     * the NOT NULL constraint that PRIMARY KEY implies. Uniqueness is handled by the
     * unique index in {@code FileIndexStore}.
     */
    private void rejectNullPrimaryKey(String database, String table, Object[] values) {
        TableMetadata metadata = catalogManager.getTable(database, table).orElse(null);
        if (metadata == null || metadata.primaryKeyColumn().isEmpty()) {
            return;
        }
        String pkCol = metadata.primaryKeyColumn().get();
        List<ColumnMetadata> columns = metadata.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(pkCol)) {
                if (i < values.length && values[i] == null) {
                    throw new IllegalArgumentException(
                            "PRIMARY KEY column '" + pkCol + "' cannot be null");
                }
                return;
            }
        }
    }

    private ColumnType[] columnTypes(String database, String table) {
        TableMetadata metadata = catalogManager.getTable(database, table).orElseThrow(
                () -> new IllegalStateException("table not in catalog: " + database + "." + table)
        );
        return metadata.columns().stream().map(ColumnMetadata::type).toArray(ColumnType[]::new);
    }

    private static String ibdFile(String database, String table) {
        return TableHeapFiles.ibdPath(database, table);
    }

    private static final class HeapState {
        private final RidMap ridMap = new InMemoryRidMap();
        private long nextRowId = 1;
        private int lastPageId = -1;
        private boolean scannedFromDisk;
    }
}
