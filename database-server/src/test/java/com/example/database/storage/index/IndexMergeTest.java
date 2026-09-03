package com.example.database.storage.index;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.page.Rid;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexMergeTest {

    private static final int PAGE_SIZE = 512;

    @TempDir
    Path tempDir;

    private FileIndexStore store;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        DefaultPhysicalStorage storage = new DefaultPhysicalStorage(dataDirectory, PAGE_SIZE);
        storage.createDirectory("shop/users");
        DefaultBufferPool pool = new DefaultBufferPool(storage, 64);
        store = new FileIndexStore(pool, storage);
        IndexMetadata index = IndexMetadata.define("idx_users_id", List.of(1));
        store.createIndex("shop", "users", index, new ColumnType[]{ColumnType.INT});
    }

    @Test
    void heavyDeleteStillFindsRemainingKeys() {
        ColumnType[] types = {ColumnType.INT};
        for (int i = 0; i < 100; i++) {
            store.insert("shop", "users", "idx_users_id", new Object[]{i}, new Rid(0, i));
        }
        for (int i = 0; i < 90; i++) {
            store.delete("shop", "users", "idx_users_id", new Object[]{i}, new Rid(0, i));
        }
        for (int i = 90; i < 100; i++) {
            List<Rid> hits = collect(store.lookupEquals("shop", "users", "idx_users_id", new Object[]{i}));
            assertEquals(1, hits.size(), "missing key " + i);
            assertEquals(new Rid(0, i), hits.get(0));
        }
        IndexRange open = new IndexRange(new Object[]{90}, true, null, false, 1);
        List<Rid> range = collect(store.lookupRange("shop", "users", "idx_users_id", open));
        assertEquals(10, range.size());
        assertTrue(range.get(0).slotId() <= range.get(9).slotId());
    }

    private static List<Rid> collect(Iterator<Rid> iterator) {
        List<Rid> out = new ArrayList<>();
        iterator.forEachRemaining(out::add);
        return out;
    }
}
