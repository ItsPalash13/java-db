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

/**
 * Verifies B+ tree internal-node merge/borrow after heavy deletes.
 * Uses a small page size to force a tall tree (height >= 3) with few keys,
 * then deletes most keys and checks all remaining keys are still scannable.
 */
class InternalMergeRebalanceTest {

    // Small page size forces splits quickly, creating height >= 3 with ~100 keys.
    private static final int PAGE_SIZE = 256;

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
        storage.createDirectory("shop/items");
        pool = new DefaultBufferPool(storage, 128);
        store = new FileIndexStore(pool, storage);
        IndexMetadata index = IndexMetadata.define("idx_items_id", List.of(1));
        store.createIndex("shop", "items", index, new ColumnType[]{ColumnType.INT});
    }

    @Test
    void deleteHeavilyThenAllRemainingKeysAreFound() {
        int total = 200;
        // Insert enough keys to build a tree with height >= 3.
        for (int i = 0; i < total; i++) {
            store.insert("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
        }

        // Delete 80% of keys — should trigger leaf merges cascading into internal merges.
        List<Integer> deleted = new ArrayList<>();
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (i % 5 != 0) {
                store.delete("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
                deleted.add(i);
            } else {
                kept.add(i);
            }
        }

        // Verify all remaining keys are found via equality lookup.
        for (int k : kept) {
            List<Rid> hits = collect(store.lookupEquals("shop", "items", "idx_items_id", new Object[]{k}));
            assertEquals(1, hits.size(), "key " + k + " should be found");
            assertEquals(new Rid(0, k), hits.get(0));
        }

        // Verify deleted keys are not found.
        for (int d : deleted) {
            List<Rid> hits = collect(store.lookupEquals("shop", "items", "idx_items_id", new Object[]{d}));
            assertTrue(hits.isEmpty(), "deleted key " + d + " should not be found");
        }
    }

    @Test
    void deleteAllKeysThenTreeIsEmpty() {
        int total = 100;
        for (int i = 0; i < total; i++) {
            store.insert("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
        }
        for (int i = 0; i < total; i++) {
            store.delete("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
        }
        // No keys should be found.
        for (int i = 0; i < total; i++) {
            List<Rid> hits = collect(store.lookupEquals("shop", "items", "idx_items_id", new Object[]{i}));
            assertTrue(hits.isEmpty(), "all keys should be deleted");
        }
    }

    @Test
    void deleteAndReinsertAfterMerge() {
        int total = 100;
        for (int i = 0; i < total; i++) {
            store.insert("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
        }
        // Delete all.
        for (int i = 0; i < total; i++) {
            store.delete("shop", "items", "idx_items_id", new Object[]{i}, new Rid(0, i));
        }
        // Re-insert a subset.
        for (int i = 0; i < 20; i++) {
            store.insert("shop", "items", "idx_items_id", new Object[]{i * 10}, new Rid(1, i));
        }
        for (int i = 0; i < 20; i++) {
            List<Rid> hits = collect(store.lookupEquals("shop", "items", "idx_items_id", new Object[]{i * 10}));
            assertEquals(1, hits.size(), "re-inserted key " + (i * 10) + " should be found");
        }
    }

    private static <T> List<T> collect(Iterator<T> iterator) {
        List<T> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }
}
