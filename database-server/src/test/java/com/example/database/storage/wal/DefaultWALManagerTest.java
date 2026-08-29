package com.example.database.storage.wal;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWALManagerTest {

    @TempDir
    Path tempDir;

    private PhysicalStorage physicalStorage;
    private DefaultWALManager wal;

    @BeforeEach
    void setUp() {
        physicalStorage = new DefaultPhysicalStorage(new DataDirectory(tempDir));
        wal = new DefaultWALManager(physicalStorage);
    }

    @Test
    void flushWritesPendingRecordsAndDiscardClearsUnflushed() {
        wal.append(WalRecord.createDatabase(1, "shop"));
        wal.discardPending();
        wal.flush();
        assertFalse(physicalStorage.exists(DefaultWALManager.WAL_FILE));

        wal.append(WalRecord.createDatabase(1, "shop"));
        wal.append(WalRecord.commit(1));
        wal.flush();
        assertTrue(physicalStorage.exists(DefaultWALManager.WAL_FILE));
        assertTrue(physicalStorage.read(DefaultWALManager.WAL_FILE).length > 0);
    }

    @Test
    void replayCreatesMissingTableFromDurableWal() {
        DefaultCatalogManager catalog = new DefaultCatalogManager(physicalStorage);
        catalog.createDatabase("shop");

        // Simulate: WAL flushed with CREATE TABLE, catalog.json never written.
        wal.append(WalRecord.createTable(
                1,
                "shop",
                "users",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.append(WalRecord.commit(1));
        wal.flush();

        DefaultCatalogManager recovered = new DefaultCatalogManager(physicalStorage);
        recovered.load();
        assertFalse(recovered.tableExists("shop", "users"));

        wal.replay(recovered);
        assertTrue(recovered.tableExists("shop", "users"));
        assertEquals(1, recovered.getTable("shop", "users").orElseThrow().tableId().orElseThrow());
        ColumnMetadata id = recovered.getTable("shop", "users").orElseThrow().columns().get(0);
        assertEquals("id", id.name());
        assertEquals(ColumnType.INT, id.type());
    }

    @Test
    void replayIgnoresUncommittedTxnGroup() {
        DefaultCatalogManager catalog = new DefaultCatalogManager(physicalStorage);
        catalog.createDatabase("shop");

        wal.append(WalRecord.createTable(
                9,
                "shop",
                "ghost",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.flush();

        DefaultCatalogManager recovered = new DefaultCatalogManager(physicalStorage);
        recovered.load();
        wal.replay(recovered);
        assertFalse(recovered.tableExists("shop", "ghost"));
    }

    @Test
    void replayIsIdempotentWhenCatalogAlreadyHasTable() {
        DefaultCatalogManager catalog = new DefaultCatalogManager(physicalStorage);
        catalog.createDatabase("shop");
        catalog.createTable(com.example.database.storage.catalog.TableMetadata.define(
                "shop",
                "users",
                List.of(ColumnMetadata.define("id", ColumnType.INT))
        ));

        wal.append(WalRecord.createTable(
                1,
                "shop",
                "users",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.append(WalRecord.commit(1));
        wal.flush();

        wal.replay(catalog);
        assertEquals(1, catalog.allTables().size());
        assertEquals(1, catalog.getTable("shop", "users").orElseThrow().tableId().orElseThrow());
    }

    @Test
    void replayWritesTimestampedLogUnderReplayFolder() throws Exception {
        DefaultCatalogManager catalog = new DefaultCatalogManager(physicalStorage);
        catalog.createDatabase("shop");

        wal.append(WalRecord.createTable(
                1,
                "shop",
                "users",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.append(WalRecord.commit(1));
        wal.flush();

        DefaultCatalogManager recovered = new DefaultCatalogManager(physicalStorage);
        recovered.load();
        wal.replay(recovered);

        Path replayDir = tempDir.resolve(WalReplayReport.REPLAY_DIR);
        assertTrue(Files.isDirectory(replayDir));
        try (Stream<Path> logs = Files.list(replayDir)) {
            Path logFile = logs.filter(path -> path.getFileName().toString().startsWith("replay-"))
                    .findFirst()
                    .orElseThrow();
            String text = Files.readString(logFile, StandardCharsets.UTF_8);
            assertTrue(text.contains("WAL replay at"));
            assertTrue(text.contains("FIXED"));
            assertTrue(text.contains("CREATE_TABLE txnId=1 shop.users"));
        }
    }

    @Test
    void replayWithoutWalFileStillWritesReplayLog() throws Exception {
        wal.replay(new DefaultCatalogManager(physicalStorage));

        Path replayDir = tempDir.resolve(WalReplayReport.REPLAY_DIR);
        try (Stream<Path> logs = Files.list(replayDir)) {
            assertTrue(logs.findAny().isPresent());
        }
    }
}
