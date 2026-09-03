package com.example.database.storage.wal;

import com.example.database.storage.catalog.CatalogException;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.index.IndexStore;
import com.example.database.storage.page.Rid;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;
import com.example.database.storage.table.TableStore;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only {@code wal.log} under the store root. Pending records are per-thread until
 * {@link #flush()} so rollback can discard work that never became durable.
 * <p>
 * LSN is a monotonic counter assigned at {@link #appendReturningLsn}; heap pages stamp
 * that LSN so {@link #flushUpTo} can force the log before writing {@code .ibd} bytes.
 * Recovery: {@link #replay} for catalog, {@link #redoDml} for committed logical DML.
 * Checkpoint updates {@code wal.checkpoint} and appends a CHECKPOINT line — callers
 * flush dirty pages before invoking {@link #checkpoint()}.
 */
public final class DefaultWALManager implements WALManager {

    /** Redo stream: JSON lines. Append-only — never truncated. */
    public static final String WAL_FILE = "wal.log";
    /**
     * Side-file recovery cursor ({@code maxTxnId}). Complements the CHECKPOINT line in
     * {@code wal.log}; updated in place because it is metadata, not the append-only redo stream.
     */
    public static final String CHECKPOINT_FILE = "wal.checkpoint";

    private final PhysicalStorage physicalStorage;
    // Unflushed records for the current transaction on this thread.
    private final ThreadLocal<List<WalRecord>> pending = ThreadLocal.withInitial(ArrayList::new);
    // Next LSN to assign; advanced past max on-disk LSN when the log is first scanned.
    private final AtomicLong nextLsn = new AtomicLong(1);
    // Highest LSN known durable on disk (after flush / scan).
    private final AtomicLong durableLsn = new AtomicLong(0);
    private final Object lsnInitLock = new Object();
    /**
     * Serializes appends to {@code wal.log}. Concurrent flush/checkpoint without this
     * races on read-length-then-write in {@link #appendLine} and corrupts JSON lines.
     */
    private final Object walFileLock = new Object();
    private volatile boolean lsnInitialized;

    public DefaultWALManager(PhysicalStorage physicalStorage) {
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
    }

    @Override
    public void append(WalRecord record) {
        Objects.requireNonNull(record, "record");
        ensureLsnInitialized();
        pending.get().add(record);
    }

    @Override
    public long appendReturningLsn(WalRecord record) {
        Objects.requireNonNull(record, "record");
        ensureLsnInitialized();
        long lsn = nextLsn.getAndIncrement();
        pending.get().add(record.withLsn(lsn));
        return lsn;
    }

    @Override
    public void flush() {
        ensureLsnInitialized();
        List<WalRecord> records = pending.get();
        if (records.isEmpty()) {
            if (physicalStorage.exists(WAL_FILE)) {
                physicalStorage.flush(WAL_FILE);
            }
            return;
        }
        synchronized (walFileLock) {
            try {
                ensureWalFile();
                long maxFlushed = durableLsn.get();
                for (WalRecord record : records) {
                    appendLineUnlocked(WalJson.toLine(record));
                    if (record.lsn() != null) {
                        maxFlushed = Math.max(maxFlushed, record.lsn());
                    }
                }
                physicalStorage.flush(WAL_FILE);
                final long flushedHigh = maxFlushed;
                durableLsn.updateAndGet(current -> Math.max(current, flushedHigh));
                records.clear();
            } catch (PhysicalStorageException e) {
                throw new WalException("failed to flush WAL", e);
            }
        }
    }

    @Override
    public void flushUpTo(long lsn) {
        if (lsn <= 0) {
            return;
        }
        ensureLsnInitialized();
        if (durableLsn.get() >= lsn) {
            return;
        }
        // Teaching simplification: pending is thread-local and shared with COMMIT — flush all.
        flush();
        if (durableLsn.get() < lsn && physicalStorage.exists(WAL_FILE)) {
            physicalStorage.flush(WAL_FILE);
        }
    }

    @Override
    public void discardPending() {
        pending.get().clear();
    }

    @Override
    public int replay(CatalogManager catalogManager) {
        Objects.requireNonNull(catalogManager, "catalogManager");
        ensureLsnInitialized();
        ZonedDateTime startedAt = ZonedDateTime.now();
        int maxTxnId = readCheckpointMaxTxnId();
        if (!physicalStorage.exists(WAL_FILE)) {
            WalReplayLogWriter.writeEmpty(
                    physicalStorage,
                    "WAL replay at " + startedAt + "\nwal.log: absent\nFIXED: (none)"
            );
            return maxTxnId;
        }
        byte[] bytes;
        try {
            bytes = physicalStorage.read(WAL_FILE);
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to read WAL for replay", e);
        }
        if (bytes.length == 0) {
            WalReplayLogWriter.writeEmpty(
                    physicalStorage,
                    "WAL replay at " + startedAt + "\nwal.log: empty\nFIXED: (none)"
            );
            return maxTxnId;
        }
        WalReplayReport report = new WalReplayReport();
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        int applyFrom = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            WalRecord probe = WalJson.fromLine(lines[i]);
            if (probe.op() == WalOp.CHECKPOINT) {
                applyFrom = i + 1;
                if (probe.txnId() != null) {
                    maxTxnId = Math.max(maxTxnId, probe.txnId());
                }
            }
        }
        int walLines = 0;
        Map<Integer, List<WalRecord>> bufferedByTxn = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            walLines++;
            WalRecord record = WalJson.fromLine(line);
            if (record.txnId() != null) {
                maxTxnId = Math.max(maxTxnId, record.txnId());
            }
            if (i < applyFrom) {
                continue;
            }
            if (record.op() == WalOp.CHECKPOINT) {
                continue;
            }
            if (record.op() == WalOp.COMMIT) {
                Integer txnId = record.txnId();
                if (txnId == null) {
                    throw new WalException("COMMIT record missing txnId");
                }
                List<WalRecord> buffered = bufferedByTxn.remove(txnId);
                if (buffered != null) {
                    for (WalRecord ddl : buffered) {
                        if (!ddl.op().isDml()) {
                            applyIdempotent(catalogManager, ddl, report);
                        }
                    }
                }
                continue;
            }
            if (record.op().isDml()) {
                if (record.txnId() != null) {
                    bufferedByTxn.computeIfAbsent(record.txnId(), ignored -> new ArrayList<>()).add(record);
                }
                continue;
            }
            if (record.txnId() == null) {
                applyIdempotent(catalogManager, record, report);
                continue;
            }
            bufferedByTxn.computeIfAbsent(record.txnId(), ignored -> new ArrayList<>()).add(record);
        }
        int uncommitted = bufferedByTxn.size();
        for (List<WalRecord> orphaned : bufferedByTxn.values()) {
            for (WalRecord record : orphaned) {
                report.addSkipped(record, "txn never committed");
            }
        }
        report.setWalLinesRead(walLines);
        report.setMaxTxnId(maxTxnId);
        report.setUncommittedTxnGroups(uncommitted);
        WalReplayLogWriter.write(physicalStorage, report);
        return maxTxnId;
    }

    @Override
    public void redoDml(TableStore tableStore, IndexStore indexStore) {
        Objects.requireNonNull(tableStore, "tableStore");
        Objects.requireNonNull(indexStore, "indexStore");
        ensureLsnInitialized();
        if (!physicalStorage.exists(WAL_FILE)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = physicalStorage.read(WAL_FILE);
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to read WAL for DML redo", e);
        }
        if (bytes.length == 0) {
            return;
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int applyFrom = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            WalRecord probe = WalJson.fromLine(lines[i]);
            if (probe.op() == WalOp.CHECKPOINT) {
                applyFrom = i + 1;
            }
        }
        Map<Integer, List<WalRecord>> bufferedByTxn = new HashMap<>();
        for (int i = applyFrom; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            WalRecord record = WalJson.fromLine(lines[i]);
            if (record.op() == WalOp.CHECKPOINT) {
                continue;
            }
            if (record.op() == WalOp.COMMIT) {
                Integer txnId = record.txnId();
                if (txnId == null) {
                    throw new WalException("COMMIT record missing txnId");
                }
                List<WalRecord> buffered = bufferedByTxn.remove(txnId);
                if (buffered != null) {
                    for (WalRecord dml : buffered) {
                        if (dml.op().isDml()) {
                            applyDmlIdempotent(tableStore, indexStore, dml);
                        }
                    }
                }
                continue;
            }
            if (!record.op().isDml()) {
                continue;
            }
            if (record.txnId() == null) {
                applyDmlIdempotent(tableStore, indexStore, record);
            } else {
                bufferedByTxn.computeIfAbsent(record.txnId(), ignored -> new ArrayList<>()).add(record);
            }
        }
    }

    @Override
    public int checkpoint() {
        ensureLsnInitialized();
        synchronized (walFileLock) {
            int maxTxnId = Math.max(readCheckpointMaxTxnId(), scanWalMaxTxnId());
            try {
                byte[] metaBytes = WalCheckpointJson.toBytes(new WalCheckpointMeta(maxTxnId));
                if (!physicalStorage.exists(CHECKPOINT_FILE)) {
                    physicalStorage.create(CHECKPOINT_FILE);
                }
                physicalStorage.write(CHECKPOINT_FILE, metaBytes);
                physicalStorage.flush(CHECKPOINT_FILE);
                ensureWalFile();
                appendLineUnlocked(WalJson.toLine(WalRecord.checkpoint(maxTxnId)));
                physicalStorage.flush(WAL_FILE);
                return maxTxnId;
            } catch (PhysicalStorageException e) {
                throw new WalException("failed to checkpoint WAL", e);
            }
        }
    }

    private void ensureLsnInitialized() {
        if (lsnInitialized) {
            return;
        }
        synchronized (lsnInitLock) {
            if (lsnInitialized) {
                return;
            }
            long maxLsn = 0;
            if (physicalStorage.exists(WAL_FILE)) {
                try {
                    byte[] bytes = physicalStorage.read(WAL_FILE);
                    if (bytes.length > 0) {
                        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
                            if (line.isBlank()) {
                                continue;
                            }
                            WalRecord record = WalJson.fromLine(line);
                            if (record.lsn() != null) {
                                maxLsn = Math.max(maxLsn, record.lsn());
                            }
                        }
                    }
                } catch (PhysicalStorageException e) {
                    throw new WalException("failed to scan WAL for LSN", e);
                }
            }
            durableLsn.set(maxLsn);
            nextLsn.set(maxLsn + 1);
            lsnInitialized = true;
        }
    }

    private int readCheckpointMaxTxnId() {
        if (!physicalStorage.exists(CHECKPOINT_FILE)) {
            return 0;
        }
        try {
            byte[] bytes = physicalStorage.read(CHECKPOINT_FILE);
            if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).trim().isEmpty()) {
                return 0;
            }
            return WalCheckpointJson.fromBytes(bytes).maxTxnId();
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to read wal.checkpoint", e);
        }
    }

    private int scanWalMaxTxnId() {
        if (!physicalStorage.exists(WAL_FILE)) {
            return 0;
        }
        try {
            byte[] bytes = physicalStorage.read(WAL_FILE);
            if (bytes.length == 0) {
                return 0;
            }
            int maxTxnId = 0;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                WalRecord record = WalJson.fromLine(line);
                if (record.txnId() != null) {
                    maxTxnId = Math.max(maxTxnId, record.txnId());
                }
            }
            return maxTxnId;
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to scan WAL for checkpoint", e);
        }
    }

    private void ensureWalFile() {
        if (!physicalStorage.exists(WAL_FILE)) {
            physicalStorage.create(WAL_FILE);
        }
    }

    private void appendLine(byte[] line) {
        synchronized (walFileLock) {
            appendLineUnlocked(line);
        }
    }

    /** Caller must hold {@link #walFileLock}. */
    private void appendLineUnlocked(byte[] line) {
        byte[] existing = physicalStorage.exists(WAL_FILE)
                ? physicalStorage.read(WAL_FILE)
                : new byte[0];
        physicalStorage.write(WAL_FILE, existing.length, line);
    }

    private static void applyDmlIdempotent(TableStore tableStore, IndexStore indexStore, WalRecord record) {
        switch (record.op()) {
            case INSERT_ROW -> {
                long rowId = record.rowId();
                if (tableStore.findByRowId(record.database(), record.table(), rowId).isEmpty()) {
                    // Same as undo of DELETE: fixed rowId without allocating a new id.
                    tableStore.restoreRow(
                            record.database(),
                            record.table(),
                            new com.example.database.processor.executor.engine.volcano.Tuple(
                                    rowId, record.valuesArray()
                            )
                    );
                }
            }
            case UPDATE_ROW -> {
                if (tableStore.findByRowId(record.database(), record.table(), record.rowId()).isPresent()) {
                    tableStore.update(
                            record.database(),
                            record.table(),
                            record.rowId(),
                            record.valuesArray()
                    );
                } else {
                    tableStore.restoreRow(
                            record.database(),
                            record.table(),
                            new com.example.database.processor.executor.engine.volcano.Tuple(
                                    record.rowId(), record.valuesArray()
                            )
                    );
                }
            }
            case DELETE_ROW -> tableStore.delete(record.database(), record.table(), record.rowId());
            case INDEX_INSERT -> {
                Rid rid = record.rid();
                if (rid != null) {
                    try {
                        indexStore.insert(
                                record.database(),
                                record.table(),
                                record.name(),
                                record.valuesArray(),
                                rid
                        );
                    } catch (RuntimeException ignored) {
                        // Idempotent after IndexPageWal or prior redo.
                    }
                }
            }
            case INDEX_DELETE -> {
                Rid rid = record.rid();
                if (rid != null) {
                    try {
                        indexStore.delete(
                                record.database(),
                                record.table(),
                                record.name(),
                                record.valuesArray(),
                                rid
                        );
                    } catch (RuntimeException ignored) {
                        // Idempotent: key may already be absent.
                    }
                }
            }
            default -> throw new WalException("not a DML op: " + record.op());
        }
    }

    private static void applyIdempotent(CatalogManager catalog, WalRecord record, WalReplayReport report) {
        try {
            switch (record.op()) {
                case CREATE_DATABASE -> {
                    if (!catalog.databaseExists(record.database())) {
                        catalog.createDatabase(record.database());
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "database already exists");
                    }
                }
                case DROP_DATABASE -> {
                    if (catalog.databaseExists(record.database())
                            && catalog.allTables().stream()
                            .noneMatch(table -> table.database().equals(record.database()))) {
                        catalog.dropDatabase(record.database());
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "database missing or not empty");
                    }
                }
                case CREATE_TABLE -> {
                    if (!catalog.tableExists(record.database(), record.table())) {
                        List<ColumnMetadata> columns = new ArrayList<>();
                        for (WalRecord.ColumnPayload column : record.columns()) {
                            columns.add(ColumnMetadata.define(column.name(), column.type(), column.nullable()));
                        }
                        catalog.createTable(TableMetadata.define(record.database(), record.table(), columns));
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "table already exists");
                    }
                }
                case DROP_TABLE -> {
                    if (catalog.tableExists(record.database(), record.table())) {
                        catalog.dropTable(record.database(), record.table());
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "table already missing");
                    }
                }
                case ADD_COLUMN -> {
                    WalRecord.ColumnPayload column = record.columns().get(0);
                    var tableOpt = catalog.getTable(record.database(), record.table());
                    if (tableOpt.isEmpty()) {
                        report.addSkipped(record, "table missing");
                        break;
                    }
                    boolean exists = tableOpt.orElseThrow().columns().stream()
                            .anyMatch(existing -> existing.name().equals(column.name()));
                    if (!exists) {
                        catalog.addColumn(
                                record.database(),
                                record.table(),
                                ColumnMetadata.define(column.name(), column.type(), column.nullable())
                        );
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "column already exists");
                    }
                }
                case DROP_COLUMN -> {
                    var tableOpt = catalog.getTable(record.database(), record.table());
                    if (tableOpt.isEmpty()) {
                        report.addSkipped(record, "table missing");
                        break;
                    }
                    boolean exists = tableOpt.orElseThrow().columns().stream()
                            .anyMatch(existing -> existing.name().equals(record.name()));
                    if (exists && tableOpt.orElseThrow().columns().size() > 1) {
                        catalog.dropColumn(record.database(), record.table(), record.name());
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "column missing or last column");
                    }
                }
                case CREATE_INDEX -> {
                    if (!catalog.tableExists(record.database(), record.table())) {
                        report.addSkipped(record, "table missing");
                        break;
                    }
                    boolean exists = catalog.getTable(record.database(), record.table())
                            .orElseThrow()
                            .indexes()
                            .stream()
                            .anyMatch(index -> index.name().equals(record.name()));
                    if (!exists) {
                        catalog.createIndex(
                                record.database(),
                                record.table(),
                                IndexMetadata.define(record.name(), record.columnIds())
                        );
                        report.addFixed(record);
                    } else {
                        report.addSkipped(record, "index already exists");
                    }
                }
                case DROP_INDEX -> {
                    try {
                        catalog.dropIndex(record.name());
                        report.addFixed(record);
                    } catch (CatalogException e) {
                        report.addSkipped(record, e.getMessage());
                    }
                }
                case INSERT_ROW, UPDATE_ROW, DELETE_ROW, INDEX_INSERT, INDEX_DELETE -> {
                    // Catalog path never applies DML.
                }
                case COMMIT, CHECKPOINT -> {
                    // Handled in replay scan.
                }
            }
        } catch (CatalogException e) {
            throw new WalException("WAL replay failed for " + record.op() + ": " + e.getMessage(), e);
        }
    }
}
