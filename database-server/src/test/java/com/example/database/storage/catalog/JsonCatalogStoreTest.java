package com.example.database.storage.catalog;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCatalogStoreTest {

    @TempDir
    Path tempDir;

    private Path storeRoot;
    private CatalogStore store;

    @BeforeEach
    void setUp() {
        storeRoot = tempDir.resolve("store");
        DataDirectory dataDirectory = new DataDirectory(storeRoot);
        dataDirectory.ensureExists();
        PhysicalStorage physicalStorage = new DefaultPhysicalStorage(dataDirectory);
        store = new JsonCatalogStore(physicalStorage);
    }

    @Test
    void loadIsEmptyWhenNoTableCatalogsExist() {
        store.createDatabase("shop");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveTableRoundTripsTablesAndIds() {
        store.createDatabase("shop");
        TableMetadata users = new TableMetadata(
                1,
                "shop",
                "users",
                List.of(
                        new ColumnMetadata(1, "id", ColumnType.INT, true),
                        new ColumnMetadata(2, "name", ColumnType.VARCHAR, true)
                )
        );

        store.saveTable(users);

        assertTrue(Files.isRegularFile(storeRoot.resolve("shop").resolve("users").resolve("catalog.json")));
        List<TableMetadata> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(users, loaded.get(0));
    }

    @Test
    void saveTableUpsertsSameTableFile() {
        store.createDatabase("shop");
        store.saveTable(new TableMetadata(
                1,
                "shop",
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true))
        ));
        TableMetadata updated = new TableMetadata(
                1,
                "shop",
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
    void saveTableRoundTripsIndexDefinitions() {
        store.createDatabase("shop");
        TableMetadata users = new TableMetadata(
                1,
                "shop",
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true)),
                List.of(IndexMetadata.define("idx_users_id", List.of(1)))
        );

        store.saveTable(users);

        List<TableMetadata> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(users, loaded.get(0));
    }

    @Test
    void loadTreatsMissingIndexesAsEmpty() {
        store.createDatabase("shop");
        Path catalog = storeRoot.resolve("shop").resolve("users").resolve("catalog.json");
        try {
            Files.createDirectories(catalog.getParent());
            Files.writeString(catalog, """
                    {"tableId":1,"name":"users","columns":[{"columnId":1,"name":"id","type":"INT","nullable":true}]}
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        TableMetadata loaded = store.load().get(0);
        assertTrue(loaded.indexes().isEmpty());
    }

    @Test
    void dropTableRemovesCatalogFileAndDirectory() {
        store.createDatabase("shop");
        store.saveTable(new TableMetadata(
                1,
                "shop",
                "users",
                List.of(new ColumnMetadata(1, "id", ColumnType.INT, true))
        ));

        store.dropTable("shop", "users");

        assertTrue(store.load().isEmpty());
        assertFalse(Files.isDirectory(storeRoot.resolve("shop").resolve("users")));
        assertTrue(Files.isDirectory(storeRoot.resolve("shop")));
    }

    @Test
    void loadIgnoresRootCatalogJsonFile() {
        store.createDatabase("shop");
        Path leftover = storeRoot.resolve("catalog.json");
        try {
            Files.writeString(leftover, "{\"tables\":[]}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(store.load().isEmpty());
        assertEquals(List.of("shop"), store.loadDatabases());
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
