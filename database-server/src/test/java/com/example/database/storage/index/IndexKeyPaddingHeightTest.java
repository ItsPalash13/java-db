package com.example.database.storage.index;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.page.Rid;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With fat keys, a modest insert count should raise tree height without changing lookup semantics.
 */
class IndexKeyPaddingHeightTest {

    @TempDir
    Path tempDir;

    @AfterEach
    @SuppressWarnings("unused") // invoked by JUnit
    void resetPadding() {
        IndexKeyCodec.setKeyPaddingBytes(0);
    }

    @Test
    void paddingMakesHeightThreeByOneThousandInsertsAndLookupsStillWork() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        DefaultPhysicalStorage storage = new DefaultPhysicalStorage(dataDirectory, 8192);
        storage.createDirectory("shop/users");
        DefaultBufferPool pool = new DefaultBufferPool(storage, 256);
        FileIndexStore store = new FileIndexStore(pool, storage, 256);
        IndexMetadata index = IndexMetadata.define("idx_users_name", List.of(1));
        store.createIndex("shop", "users", index, new ColumnType[]{ColumnType.VARCHAR});

        int n = 1000;
        for (int i = 1; i <= n; i++) {
            store.insert("shop", "users", "idx_users_name", new Object[]{"user" + i}, new Rid(1, i));
        }

        // Spot-check equality after many splits.
        List<Rid> hit = collect(store.lookupEquals("shop", "users", "idx_users_name", new Object[]{"user577"}));
        assertEquals(1, hit.size());
        assertEquals(new Rid(1, 577), hit.get(0));

        // Height should exceed 2 with pad=256 @ 8 KiB (root must split).
        int height = readHeight(pool);
        assertTrue(height >= 3, "expected height >= 3, got " + height);
    }

    private static int readHeight(DefaultBufferPool pool) {
        // Pin meta page 0 directly.
        var frame = pool.pin(new com.example.database.storage.bufferpool.PageId(
                IndexFiles.idxPath("shop", "users", "idx_users_name"), 0));
        try {
            pool.latchShared(frame);
            try {
                return IndexMetaPage.wrap(frame.data()).height();
            } finally {
                pool.unlatch(frame);
            }
        } finally {
            pool.unpin(frame);
        }
    }

    private static <T> List<T> collect(Iterator<T> iterator) {
        List<T> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }
}
