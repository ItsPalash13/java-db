package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 file heap: pin/latch through BufferPool, RidMap rebuild on open.
 */
class FileTableStoreTest {

    private static final int PAGE = 256;

    @TempDir
    Path tempDir;

    private DefaultPhysicalStorage storage;
    private DefaultBufferPool pool;
    private DefaultCatalogManager catalog;
    private FileTableStore store;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        storage = new DefaultPhysicalStorage(dataDirectory, PAGE);
        pool = new DefaultBufferPool(storage, 8);
        catalog = new DefaultCatalogManager(storage);
        catalog.createDatabase("shop");
        catalog.createTable(TableMetadata.define(
                "shop",
                "users",
                List.of(
                        ColumnMetadata.define("id", ColumnType.INT),
                        ColumnMetadata.define("name", ColumnType.VARCHAR)
                )
        ));
        store = new FileTableStore(catalog, pool, storage);
        store.prepareTable("shop", "users");
    }

    @Test
    void insertScanUpdateDeleteAndDrop() {
        Tuple first = store.insert("shop", "users", new Object[]{1, "Ada"});
        Tuple second = store.insert("shop", "users", new Object[]{2, "Bob"});
        assertEquals(1L, first.rowId());
        assertEquals(2L, second.rowId());

        List<Tuple> scanned = collect(store.scan("shop", "users"));
        assertEquals(2, scanned.size());
        assertEquals("Ada", scanned.get(0).get(2));

        store.update("shop", "users", first.rowId(), new Object[]{1, "Ada Lovelace"});
        assertEquals(
                "Ada Lovelace",
                store.findByRowId("shop", "users", first.rowId()).orElseThrow().get(2)
        );

        store.delete("shop", "users", second.rowId());
        assertEquals(1, collect(store.scan("shop", "users")).size());

        pool.flushAll();
        store.dropTable("shop", "users");
        assertFalse(storage.exists(TableHeapFiles.ibdPath("shop", "users")));
        assertFalse(store.scan("shop", "users").hasNext());
    }

    @Test
    void rowsSurviveNewStoreInstanceAfterFlush() {
        store.insert("shop", "users", new Object[]{1, "Ada"});
        store.insert("shop", "users", new Object[]{2, "Bob"});
        pool.flushAll();

        DefaultBufferPool freshPool = new DefaultBufferPool(storage, 8);
        FileTableStore reopened = new FileTableStore(catalog, freshPool, storage);
        List<Tuple> rows = collect(reopened.scan("shop", "users"));
        assertEquals(2, rows.size());
        assertEquals(1L, rows.get(0).rowId());
        assertEquals("Ada", rows.get(0).get(2));
        assertEquals(2L, rows.get(1).rowId());

        Tuple third = reopened.insert("shop", "users", new Object[]{3, "Carol"});
        assertEquals(3L, third.rowId());
    }

    @Test
    void growingUpdateRelocatesRow() {
        Tuple row = store.insert("shop", "users", new Object[]{1, "A"});
        store.update("shop", "users", row.rowId(), new Object[]{1, "Much longer name than before"});
        Tuple reread = store.findByRowId("shop", "users", row.rowId()).orElseThrow();
        assertEquals("Much longer name than before", reread.get(2));
        assertEquals(1, collect(store.scan("shop", "users")).size());
    }

    @Test
    void appendNewPageWhenLastPageFull() {
        String longName = "x".repeat(180);
        store.insert("shop", "users", new Object[]{1, longName});
        store.insert("shop", "users", new Object[]{2, longName});
        assertEquals(2, collect(store.scan("shop", "users")).size());
        assertTrue(storage.byteLength(TableHeapFiles.ibdPath("shop", "users")) >= PAGE * 2L);
    }

    @Test
    void findByRowIdUsesRidMap() {
        Tuple row = store.insert("shop", "users", new Object[]{42, "Z"});
        Tuple found = store.findByRowId("shop", "users", row.rowId()).orElseThrow();
        assertEquals(42, found.get(1));
        assertEquals("Z", found.get(2));
    }

    private static List<Tuple> collect(Iterator<Tuple> iterator) {
        List<Tuple> rows = new ArrayList<>();
        iterator.forEachRemaining(rows::add);
        return rows;
    }
}
