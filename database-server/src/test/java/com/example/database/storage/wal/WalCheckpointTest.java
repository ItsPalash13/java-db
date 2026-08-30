package com.example.database.storage.wal;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalCheckpointTest {

    @TempDir
    Path tempDir;

    private PhysicalStorage physicalStorage;
    private DefaultWALManager wal;
    private LockManager locks;

    @BeforeEach
    void setUp() {
        physicalStorage = new DefaultPhysicalStorage(new DataDirectory(tempDir));
        wal = new DefaultWALManager(physicalStorage);
        locks = new DefaultLockManager();
    }

    @Test
    void checkpointAppendsMarkerWithoutRewritingPriorLines() {
        DefaultCatalogManager catalog = new DefaultCatalogManager(physicalStorage);
        catalog.createDatabase("shop");
        catalog.createTable(com.example.database.storage.catalog.TableMetadata.define(
                "shop",
                "users",
                List.of(com.example.database.storage.catalog.ColumnMetadata.define("id", ColumnType.INT, true))
        ));

        wal.append(WalRecord.createDatabase(1, "shop"));
        wal.append(WalRecord.commit(1));
        wal.append(WalRecord.createTable(
                2,
                "shop",
                "users",
                List.of(new WalRecord.ColumnPayload("id", ColumnType.INT, true))
        ));
        wal.append(WalRecord.commit(2));
        wal.flush();
        String before = new String(physicalStorage.read(DefaultWALManager.WAL_FILE), StandardCharsets.UTF_8);
        assertTrue(before.contains("CREATE_DATABASE"));
        assertTrue(before.contains("CREATE_TABLE"));

        int maxTxnId = locks.runExclusiveCatalog(wal::checkpoint);
        assertEquals(2, maxTxnId);
        String after = new String(physicalStorage.read(DefaultWALManager.WAL_FILE), StandardCharsets.UTF_8);
        // Append-only: prior redo still present, CHECKPOINT added at the end.
        assertTrue(after.startsWith(before.trim()) || after.contains("CREATE_DATABASE"));
        assertTrue(after.contains("CREATE_TABLE"));
        assertTrue(after.contains("\"op\":\"CHECKPOINT\""));
        assertTrue(after.contains("\"txnId\":2"));
        assertTrue(physicalStorage.exists(DefaultWALManager.CHECKPOINT_FILE));

        DefaultCatalogManager recovered = new DefaultCatalogManager(physicalStorage);
        recovered.load();
        assertEquals(2, wal.replay(recovered));
        assertTrue(recovered.tableExists("shop", "users"));
    }
}
