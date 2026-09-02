package com.example.database.storage.page;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.catalog.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapPageTest {

    private static final ColumnType[] USERS = {
            ColumnType.INT, ColumnType.VARCHAR, ColumnType.BOOLEAN
    };

    @Test
    void emptyPageHasHeaderAndFullFreeSpace() {
        HeapPage page = HeapPage.createEmpty(7, 256);
        assertEquals(7, page.pageId());
        assertEquals(0, page.slotCount());
        assertEquals(PageLayout.HEADER_SIZE, page.lower());
        assertEquals(256, page.upper());
        assertEquals(256 - PageLayout.HEADER_SIZE, page.freeSpace());
    }

    @Test
    void insertReadUpdateDeleteRoundTrip() {
        HeapPage page = HeapPage.createEmpty(0, 512);
        RidMap ridMap = new InMemoryRidMap();

        int slot0 = page.insert(1L, new Object[]{1, "Ada", true}, USERS);
        ridMap.put(1L, new Rid(page.pageId(), slot0));
        int slot1 = page.insert(2L, new Object[]{2, "Bob", false}, USERS);
        ridMap.put(2L, new Rid(page.pageId(), slot1));

        assertEquals(0, slot0);
        assertEquals(1, slot1);
        assertEquals(2, page.slotCount());

        Tuple ada = page.read(slot0, USERS).orElseThrow();
        assertEquals(1L, ada.rowId());
        assertEquals(1, ada.get(1));
        assertEquals("Ada", ada.get(2));
        assertEquals(true, ada.get(3));

        page.update(slot0, 1L, new Object[]{1, "Ada", false}, USERS);
        assertEquals(false, page.read(slot0, USERS).orElseThrow().get(3));

        page.delete(slot1);
        assertTrue(page.read(slot1, USERS).isEmpty());
        assertFalse(page.isLive(slot1));
        // Tombstone keeps the directory index; next insert gets slot 2, not reuse of 1.
        int slot2 = page.insert(3L, new Object[]{3, "Cy", true}, USERS);
        assertEquals(2, slot2);
        assertEquals(3, page.slotCount());

        assertEquals(new Rid(0, 0), ridMap.get(1L).orElseThrow());
    }

    @Test
    void nullColumnsOmitTypedBytes() {
        byte[] withNull = RowCodec.encode(9L, new Object[]{null, "x", null}, USERS);
        byte[] allPresent = RowCodec.encode(9L, new Object[]{1, "x", true}, USERS);
        assertTrue(withNull.length < allPresent.length);

        Tuple decoded = RowCodec.decode(withNull, USERS);
        assertEquals(9L, decoded.rowId());
        assertArrayEquals(new Object[]{null, "x", null}, decoded.values());
    }

    @Test
    void wrapRejectsBadMagicAndRoundTripsBytes() {
        HeapPage page = HeapPage.createEmpty(3, 256);
        page.insert(1L, new Object[]{1, "A", true}, USERS);
        byte[] image = page.toBytes();

        HeapPage again = HeapPage.wrap(image);
        assertEquals(1, again.slotCount());
        assertEquals("A", again.read(0, USERS).orElseThrow().get(2));

        image[0] = 0;
        assertThrows(PageLayoutException.class, () -> HeapPage.wrap(image));
    }

    @Test
    void insertFailsWhenPageFull() {
        // Tiny page: header + one slot + a short row barely fits; second insert fails.
        HeapPage page = HeapPage.createEmpty(0, 64);
        page.insert(1L, new Object[]{1, "A", true}, USERS);
        assertThrows(
                PageLayoutException.class,
                () -> page.insert(2L, new Object[]{2, "BBBBBBBBBB", false}, USERS));
    }

    @Test
    void updateRejectsGrowthBeyondSlot() {
        HeapPage page = HeapPage.createEmpty(0, 256);
        int slot = page.insert(1L, new Object[]{1, "A", true}, USERS);
        assertThrows(
                PageLayoutException.class,
                () -> page.update(slot, 1L, new Object[]{1, "A-much-longer-name", true}, USERS));
    }

    @Test
    void scanLiveSkipsTombstones() {
        HeapPage page = HeapPage.createEmpty(0, 512);
        page.insert(1L, new Object[]{1, "Ada", true}, USERS);
        page.insert(2L, new Object[]{2, "Bob", false}, USERS);
        page.delete(0);
        List<Tuple> live = page.scanLive(USERS);
        assertEquals(1, live.size());
        assertEquals(2L, live.get(0).rowId());
    }

    @Test
    void longLiteralFitsIntColumn() {
        Tuple t = RowCodec.decode(RowCodec.encode(1L, new Object[]{42L, "n", true}, USERS), USERS);
        assertEquals(42, t.get(1));
    }
}
