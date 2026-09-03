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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexSortBuildTest {

    private static final int PAGE_SIZE = 512;

    @TempDir
    Path tempDir;

    private FileIndexStore indexStore;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        DefaultPhysicalStorage storage = new DefaultPhysicalStorage(dataDirectory, PAGE_SIZE);
        storage.createDirectory("shop/users");
        DefaultBufferPool pool = new DefaultBufferPool(storage, 64);
        indexStore = new FileIndexStore(pool, storage);
        IndexMetadata index = IndexMetadata.define("idx_users_id", List.of(1));
        indexStore.createIndex("shop", "users", index, new ColumnType[]{ColumnType.INT});
    }

    @Test
    void sortBuildMatchesEqualityAndRangeAfterShuffledKeys() {
        ColumnType[] keyTypes = {ColumnType.INT};
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            ids.add(i);
        }
        Collections.shuffle(ids, new Random(7));
        List<BTreeLeafPage.LeafEntry> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            byte[] keyBytes = IndexKeyCodec.encode(new Object[]{id}, keyTypes);
            entries.add(new BTreeLeafPage.LeafEntry(keyBytes, new Rid(0, i), new Object[]{id}));
        }
        entries.sort(Comparator.comparing(BTreeLeafPage.LeafEntry::keyBytes, (a, b) -> IndexKeyCodec.compare(a, b, keyTypes)));
        indexStore.bulkLoadSorted("shop", "users", "idx_users_id", entries, false);

        List<Rid> eq = collect(indexStore.lookupEquals("shop", "users", "idx_users_id", new Object[]{42}));
        assertEquals(1, eq.size());

        IndexRange range = new IndexRange(new Object[]{190}, false, null, false, 1);
        List<Rid> rangeHits = collect(indexStore.lookupRange("shop", "users", "idx_users_id", range));
        assertEquals(10, rangeHits.size());
    }

    @Test
    void uniqueSortBuildRejectsAdjacentDuplicates() {
        ColumnType[] keyTypes = {ColumnType.INT};
        byte[] key = IndexKeyCodec.encode(new Object[]{1}, keyTypes);
        List<BTreeLeafPage.LeafEntry> entries = List.of(
                new BTreeLeafPage.LeafEntry(key, new Rid(0, 0), new Object[]{1}),
                new BTreeLeafPage.LeafEntry(key, new Rid(0, 1), new Object[]{1})
        );
        assertThrows(IndexStoreException.class, () ->
                indexStore.bulkLoadSorted("shop", "users", "idx_users_id", entries, true)
        );
    }

    private static List<Rid> collect(Iterator<Rid> iterator) {
        List<Rid> out = new ArrayList<>();
        iterator.forEachRemaining(out::add);
        return out;
    }
}
