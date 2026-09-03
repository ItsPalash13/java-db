package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexKeyCodecTest {

    @Test
    void encodeDecodeRoundTripPreservesOrder() {
        ColumnType[] types = {ColumnType.INT, ColumnType.VARCHAR, ColumnType.BOOLEAN};
        Object[] values = {42, "ada", true};
        byte[] encoded = IndexKeyCodec.encode(values, types);
        assertArrayEquals(values, IndexKeyCodec.decode(encoded, types));
    }

    @Test
    void nullsSortBeforeNonNulls() {
        ColumnType[] types = {ColumnType.INT};
        byte[] nullKey = IndexKeyCodec.encode(new Object[]{null}, types);
        byte[] zeroKey = IndexKeyCodec.encode(new Object[]{0}, types);
        assertTrue(IndexKeyCodec.compare(nullKey, zeroKey, types) < 0);
    }

    @Test
    void compositeKeysCompareLexicographically() {
        ColumnType[] types = {ColumnType.INT, ColumnType.VARCHAR};
        byte[] left = IndexKeyCodec.encode(new Object[]{1, "a"}, types);
        byte[] right = IndexKeyCodec.encode(new Object[]{1, "b"}, types);
        assertTrue(IndexKeyCodec.compare(left, right, types) < 0);
        assertEquals(0, IndexKeyCodec.compare(left, left, types));
    }
}
