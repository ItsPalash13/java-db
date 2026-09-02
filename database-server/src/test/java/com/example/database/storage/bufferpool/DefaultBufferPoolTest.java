package com.example.database.storage.bufferpool;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.page.HeapPage;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBufferPoolTest {

    private static final int PAGE = 256;

    @TempDir
    Path tempDir;

    private DefaultPhysicalStorage storage;
    private DefaultBufferPool pool;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        storage = new DefaultPhysicalStorage(dataDirectory, PAGE);
        pool = new DefaultBufferPool(storage, 2);
        storage.create("t.ibd");
    }

    @Test
    void newPagePinHitAndFlushPersistsBytes() {
        BufferFrame f0 = pool.newPage("t.ibd");
        assertEquals(0, f0.pageId().pageId());
        pool.latchExclusive(f0);
        f0.data()[100] = 42;
        pool.markDirty(f0);
        pool.unlatch(f0);
        pool.unpin(f0);

        pool.flush(new PageId("t.ibd", 0));

        byte[] onDisk = storage.read("t.ibd", 0, PAGE);
        assertEquals(42, onDisk[100] & 0xFF);

        BufferFrame again = pool.pin(new PageId("t.ibd", 0));
        assertEquals(42, again.data()[100] & 0xFF);
        pool.unpin(again);
    }

    @Test
    void dirtyPagesBlockEvictionUntilFlushAll() {
        BufferFrame a = pool.newPage("t.ibd");
        pool.latchExclusive(a);
        a.data()[0] = 1;
        pool.markDirty(a);
        pool.unlatch(a);
        pool.unpin(a);

        BufferFrame b = pool.newPage("t.ibd");
        pool.latchExclusive(b);
        b.data()[0] = 2;
        pool.markDirty(b);
        pool.unlatch(b);
        pool.unpin(b);

        assertThrows(BufferPoolException.class, () -> pool.newPage("t.ibd"));

        pool.flushAll();
        BufferFrame c = pool.newPage("t.ibd");
        assertEquals(2, c.pageId().pageId());
        pool.unpin(c);
    }

    @Test
    void pinReloadAfterCleanEviction() {
        BufferFrame a = pool.newPage("t.ibd");
        pool.latchExclusive(a);
        a.data()[50] = 7;
        pool.markDirty(a);
        pool.unlatch(a);
        pool.unpin(a);
        pool.flushAll();

        BufferFrame b = pool.newPage("t.ibd");
        pool.unpin(b);
        BufferFrame c = pool.newPage("t.ibd");
        pool.unpin(c);

        BufferFrame reloaded = pool.pin(new PageId("t.ibd", 0));
        assertEquals(7, reloaded.data()[50] & 0xFF);
        pool.unpin(reloaded);
    }

    @Test
    void sharedLatchAllowsTwoReaders() {
        BufferFrame frame = pool.newPage("t.ibd");
        pool.latchShared(frame);
        pool.latchShared(frame);
        pool.unlatch(frame);
        pool.unlatch(frame);
        pool.unpin(frame);
    }

    @Test
    void heapPageRoundTripThroughPool() {
        BufferFrame frame = pool.newPage("t.ibd");
        pool.latchExclusive(frame);
        HeapPage page = HeapPage.wrap(frame.data());
        assertEquals(0, page.pageId());
        assertEquals(0, page.slotCount());
        pool.unlatch(frame);
        pool.unpin(frame);

        pool.flushAll();
        assertEquals(PAGE, storage.byteLength("t.ibd"));

        BufferFrame pinned = pool.pin(new PageId("t.ibd", 0));
        assertArrayEquals(storage.read("t.ibd", 0, PAGE), pinned.data());
        pool.unpin(pinned);
        assertTrue(storage.byteLength("t.ibd") >= PAGE);
    }

    @Test
    void doublePinIncrementsPinCount() {
        BufferFrame first = pool.newPage("t.ibd");
        BufferFrame second = pool.pin(first.pageId());
        assertEquals(first.index(), second.index());
        assertEquals(2, first.pinCount());
        pool.unpin(first);
        assertEquals(1, first.pinCount());
        pool.unpin(second);
        assertEquals(0, first.pinCount());
    }
}
