package com.example.database.storage.wal;

import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Persists {@link WalReplayReport} under {@code replay/} on every storage start.
 * Separate from {@code wal.log} — operator-facing recovery summary, not redo input.
 */
final class WalReplayLogWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private WalReplayLogWriter() {
    }

    static void write(PhysicalStorage physicalStorage, WalReplayReport report) {
        Objects.requireNonNull(physicalStorage, "physicalStorage");
        Objects.requireNonNull(report, "report");
        ZonedDateTime now = ZonedDateTime.now();
        try {
            physicalStorage.createDirectory(WalReplayReport.REPLAY_DIR);
            String file = WalReplayReport.REPLAY_DIR + "/replay-" + now.format(FILE_STAMP) + ".log";
            physicalStorage.create(file);
            physicalStorage.write(file, report.toLogBytes(now));
            physicalStorage.flush(file);
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to write replay log", e);
        }
    }

    static void writeEmpty(PhysicalStorage physicalStorage, String message) {
        Objects.requireNonNull(physicalStorage, "physicalStorage");
        ZonedDateTime now = ZonedDateTime.now();
        try {
            physicalStorage.createDirectory(WalReplayReport.REPLAY_DIR);
            String file = WalReplayReport.REPLAY_DIR + "/replay-" + now.format(FILE_STAMP) + ".log";
            byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
            physicalStorage.create(file);
            physicalStorage.write(file, bytes);
            physicalStorage.flush(file);
        } catch (PhysicalStorageException e) {
            throw new WalException("failed to write replay log", e);
        }
    }
}
