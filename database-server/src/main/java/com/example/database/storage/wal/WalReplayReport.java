package com.example.database.storage.wal;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of one WAL replay on storage start. Written to {@code replay/} for operators.
 */
final class WalReplayReport {

    static final String REPLAY_DIR = "replay";

    private int walLinesRead;
    private int maxTxnId;
    private int uncommittedTxnGroups;
    private final List<Entry> fixed = new ArrayList<>();
    private final List<Entry> skipped = new ArrayList<>();

    void setWalLinesRead(int walLinesRead) {
        this.walLinesRead = walLinesRead;
    }

    void setMaxTxnId(int maxTxnId) {
        this.maxTxnId = maxTxnId;
    }

    void setUncommittedTxnGroups(int uncommittedTxnGroups) {
        this.uncommittedTxnGroups = uncommittedTxnGroups;
    }

    int maxTxnId() {
        return maxTxnId;
    }

    List<Entry> fixed() {
        return List.copyOf(fixed);
    }

    void addFixed(WalRecord record) {
        fixed.add(Entry.of(record));
    }

    void addSkipped(WalRecord record, String reason) {
        skipped.add(Entry.of(record, reason));
    }

    byte[] toLogBytes(ZonedDateTime startedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        StringBuilder text = new StringBuilder();
        text.append("WAL replay at ").append(startedAt).append('\n');
        text.append("wal.log lines read: ").append(walLinesRead).append('\n');
        text.append("max txn id seen: ").append(maxTxnId).append('\n');
        text.append("uncommitted txn groups ignored: ").append(uncommittedTxnGroups).append('\n');
        text.append('\n');

        text.append("FIXED (applied to catalog — was missing after load):\n");
        if (fixed.isEmpty()) {
            text.append("  (none)\n");
        } else {
            for (Entry entry : fixed) {
                text.append("  ").append(describe(entry.record())).append('\n');
            }
        }
        text.append('\n');

        text.append("SKIPPED (already in catalog or not applicable):\n");
        if (skipped.isEmpty()) {
            text.append("  (none)\n");
        } else {
            for (Entry entry : skipped) {
                text.append("  ").append(describe(entry.record()));
                if (entry.reason() != null && !entry.reason().isBlank()) {
                    text.append(" — ").append(entry.reason());
                }
                text.append('\n');
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String describe(WalRecord record) {
        StringBuilder line = new StringBuilder();
        line.append(record.op());
        if (record.txnId() != null) {
            line.append(" txnId=").append(record.txnId());
        }
        if (record.database() != null) {
            line.append(' ').append(record.database());
        }
        if (record.table() != null) {
            line.append('.').append(record.table());
        }
        if (record.name() != null) {
            line.append(' ').append(record.name());
        }
        return line.toString();
    }

    private record Entry(WalRecord record, String reason) {
        static Entry of(WalRecord record) {
            return new Entry(record, null);
        }

        static Entry of(WalRecord record, String reason) {
            return new Entry(record, reason);
        }
    }
}
