package com.example.database.storage.wal;

import com.example.database.storage.catalog.CatalogException;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only {@code wal.log} under the store root. Pending records are per-thread until
 * {@link #flush()} so rollback can discard work that never became durable.
 */
public final class DefaultWALManager implements WALManager {

    static final String WAL_FILE = "wal.log";

    private final PhysicalStorage physicalStorage;
    // Unflushed records for the current transaction on this thread.
    private final ThreadLocal<List<WalRecord>> pending = ThreadLocal.withInitial(ArrayList::new);

    public DefaultWALManager(PhysicalStorage physicalStorage) {
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
    }

    @Override
    public void append(WalRecord record) {
        Objects.requireNonNull(record, "record");
        pending.get().add(record);
    }

    @Override
    public void flush() {
        List<WalRecord> records = pending.get();
        if (records.isEmpty()) {
            // Still force an existing log so commit is a durable barrier when the file exists.
            if (physicalStorage.exists(WAL_FILE)) {
                physicalStorage.flush(WAL_FILE);
            }
            return;
        }
        try {
            ensureWalFile();
            for (WalRecord record : records) {
                appendLine(WalJson.toLine(record));
            }
            // write() can return with bytes only in the OS cache; crash would lose intent.
            physicalStorage.flush(WAL_FILE);
            records.clear();
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to flush WAL", e);
        }
    }

    @Override
    public void discardPending() {
        pending.get().clear();
    }

    @Override
    public int replay(CatalogManager catalogManager) {
        Objects.requireNonNull(catalogManager, "catalogManager");
        ZonedDateTime startedAt = ZonedDateTime.now();
        if (!physicalStorage.exists(WAL_FILE)) {
            WalReplayLogWriter.writeEmpty(
                    physicalStorage,
                    "WAL replay at " + startedAt + "\nwal.log: absent\nFIXED: (none)"
            );
            return 0;
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
            return 0;
        }
        WalReplayReport report = new WalReplayReport();
        String text = new String(bytes, StandardCharsets.UTF_8);
        int maxTxnId = 0;
        int walLines = 0;
        Map<Integer, List<WalRecord>> bufferedByTxn = new HashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            walLines++;
            WalRecord record = WalJson.fromLine(line);
            if (record.txnId() != null) {
                maxTxnId = Math.max(maxTxnId, record.txnId());
            }
            if (record.op() == WalOp.COMMIT) {
                Integer txnId = record.txnId();
                if (txnId == null) {
                    throw new WalException("COMMIT record missing txnId");
                }
                List<WalRecord> buffered = bufferedByTxn.remove(txnId);
                if (buffered != null) {
                    for (WalRecord ddl : buffered) {
                        applyIdempotent(catalogManager, ddl, report);
                    }
                }
                continue;
            }
            if (record.txnId() == null) {
                // Legacy Step 3 lines: no txn boundary — treat as committed redo.
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

    private void ensureWalFile() {
        if (!physicalStorage.exists(WAL_FILE)) {
            physicalStorage.create(WAL_FILE);
        }
    }

    private void appendLine(byte[] line) {
        // Offset == current length is append; PhysicalStorage rejects writing past EOF with a hole.
        byte[] existing = physicalStorage.exists(WAL_FILE)
                ? physicalStorage.read(WAL_FILE)
                : new byte[0];
        physicalStorage.write(WAL_FILE, existing.length, line);
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
                case COMMIT -> {
                    // Handled in replay scan — not applied to catalog.
                }
            }
        } catch (CatalogException e) {
            throw new WalException("WAL replay failed for " + record.op() + ": " + e.getMessage(), e);
        }
    }
}
