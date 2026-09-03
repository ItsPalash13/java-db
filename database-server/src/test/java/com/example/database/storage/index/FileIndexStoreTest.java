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

class FileIndexStoreTest {

    private static final int PAGE_SIZE = 512;

    @TempDir
    Path tempDir;

    private FileIndexStore store;
    private DefaultBufferPool pool;
    private DefaultPhysicalStorage storage;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        storage = new DefaultPhysicalStorage(dataDirectory, PAGE_SIZE);
        storage.createDirectory("shop/users");
        pool = new DefaultBufferPool(storage, 16);
        store = new FileIndexStore(pool, storage);
        IndexMetadata index = IndexMetadata.define("idx_users_id", List.of(1));
        store.createIndex("shop", "users", index, new ColumnType[]{ColumnType.INT});
    }

    @Test
    void insertLookupSplitAndSurviveFlush() {
        ColumnType[] types = {ColumnType.INT};
        for (int i = 0; i < 40; i++) {
            store.insert("shop", "users", "idx_users_id", new Object[]{i}, new Rid(0, i));
        }
        List<Rid> hits = collect(store.lookupEquals("shop", "users", "idx_users_id", new Object[]{17}));
        assertEquals(1, hits.size());
        assertEquals(new Rid(0, 17), hits.get(0));

        pool.flushAll();
        FileIndexStore reopened = new FileIndexStore(pool, storage);
        reopened.registerKeyTypes("shop", "users", IndexMetadata.define("idx_users_id", List.of(1)), types);
        List<Rid> afterFlush = collect(reopened.lookupEquals("shop", "users", "idx_users_id", new Object[]{17}));
        assertEquals(1, afterFlush.size());
    }

    @Test
    void lookupRangeReturnsSortedKeysAcrossLeaves() {
        ColumnType[] types = {ColumnType.INT};
        List<Integer> keys = List.of(5, 1, 9, 3, 7, 2, 8, 4, 6);
        for (int i = 0; i < keys.size(); i++) {
            store.insert("shop", "users", "idx_users_id", new Object[]{keys.get(i)}, new Rid(0, i));
        }
        IndexRange range = new IndexRange(new Object[]{4}, true, new Object[]{7}, true, 1);
        List<Rid> hits = collect(store.lookupRange("shop", "users", "idx_users_id", range));
        assertEquals(4, hits.size());
        assertEquals(new Rid(0, keys.indexOf(4)), hits.get(0));
        assertEquals(new Rid(0, keys.indexOf(7)), hits.get(3));
    }

    @Test
    void deleteRemovesMatchingRidOnly() {
        ColumnType[] types = {ColumnType.INT};
        store.insert("shop", "users", "idx_users_id", new Object[]{7}, new Rid(1, 0));
        store.insert("shop", "users", "idx_users_id", new Object[]{7}, new Rid(1, 1));
        store.delete("shop", "users", "idx_users_id", new Object[]{7}, new Rid(1, 0));
        List<Rid> remaining = collect(store.lookupEquals("shop", "users", "idx_users_id", new Object[]{7}));
        assertEquals(1, remaining.size());
        assertEquals(new Rid(1, 1), remaining.get(0));
    }

    private static List<Rid> collect(Iterator<Rid> iterator) {
        List<Rid> out = new ArrayList<>();
        iterator.forEachRemaining(out::add);
        return out;
    }
}
