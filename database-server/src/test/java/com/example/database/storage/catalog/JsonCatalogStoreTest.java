package com.example.database.storage.catalog;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCatalogStoreTest {

    @TempDir
    Path tempDir;

    private CatalogStore store;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        PhysicalStorage physicalStorage = new DefaultPhysicalStorage(dataDirectory);
        store = new JsonCatalogStore(physicalStorage);
    }

    @Test
    void loadIsEmptyWhenCatalogFileIsMissing() {
        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveAllRoundTripsTablesAndIds() {
        TableMetadata users = new TableMetadata(
                1,
                "users",
                List.of(
                        new ColumnMetadata(1, "id", ColumnType.INT, true),
                        new ColumnMetadata(2, "name", ColumnType.VARCHAR, true)
                )
        );

        store.saveAll(List.of(users));

        List<TableMetadata> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(users, loaded.get(0));
    }

    @Test
    void saveTableUpsertsByName() {
        store.saveTable(new TableMetadata(
                1,
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true))
        ));
        TableMetadata updated = new TableMetadata(
                1,
                "users",
                List.of(
                        new ColumnMetadata(1, "id", ColumnType.INT, true),
                        new ColumnMetadata(2, "age", ColumnType.INT, true)
                )
        );

        store.saveTable(updated);

        assertEquals(List.of(updated), store.load());
    }

    @Test
    void createDatabaseRoundTripsDirectoryNames() {
        store.createDatabase("shop");
        store.createDatabase("app");

        assertEquals(List.of("app", "shop"), store.loadDatabases());

        store.dropDatabase("shop");
        assertEquals(List.of("app"), store.loadDatabases());
    }
}
