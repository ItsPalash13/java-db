package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.page.Rid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BTreeLeafPageTest {

    private static final int PAGE_SIZE = 512;

    @Test
    void sortedInsertAndSiblingLink() {
        ColumnType[] types = {ColumnType.INT};
        BTreeLeafPage left = BTreeLeafPage.createEmpty(1, PAGE_SIZE);
        left.setNextLeafPageId(42);

        left.insertSorted(IndexKeyCodec.encode(new Object[]{1}, types), new Rid(1, 0), types);
        left.insertSorted(IndexKeyCodec.encode(new Object[]{3}, types), new Rid(1, 1), types);

        List<Integer> keys = left.liveEntries(types).stream()
                .map(entry -> (Integer) IndexKeyCodec.decode(entry.keyBytes(), types)[0])
                .toList();
        assertEquals(List.of(1, 3), keys);
        assertEquals(42, left.nextLeafPageId());
    }
}
