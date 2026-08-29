package com.example.database.storage.wal;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.catalog.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash between durable WAL and catalog apply: restart load + replay restores the table.
 */
class WalReplayRestartTest {

    @TempDir
    Path tempDir;

    @Test
    void storageStartReplaysCreateTableMissingFromCatalogFiles() {
        DataDirectory dataDirectory = new DataDirectory(tempDir);
        // First engine only to create physical layout helpers; we write WAL manually.
        StorageEngine bootstrap = new DefaultStorageEngine(dataDirectory);
        bootstrap.start();
        bootstrap.catalogManager().createDatabase("shop");
        bootstrap.stop();

        DefaultWALManager wal = new DefaultWALManager(
                new com.example.database.storage.physical.DefaultPhysicalStorage(dataDirectory)
        );
        wal.append(WalRecord.createTable(
                1,
                "shop",
                "users",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.append(WalRecord.commit(1));
        wal.flush();

        StorageEngine recovered = new DefaultStorageEngine(dataDirectory);
        recovered.start();
        assertTrue(recovered.catalogManager().tableExists("shop", "users"));
        recovered.stop();
    }
}
