package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexKeyCodecTest {

    @AfterEach
    void resetPadding() {
        IndexKeyCodec.setKeyPaddingBytes(0);
    }

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

    @Test
    void keyPaddingFattensEntriesButDoesNotChangeCompareOrDecode() {
        ColumnType[] types = {ColumnType.VARCHAR};
        IndexKeyCodec.setKeyPaddingBytes(0);
        byte[] slim = IndexKeyCodec.encode(new Object[]{"user100"}, types);
        IndexKeyCodec.setKeyPaddingBytes(256);
        byte[] fat = IndexKeyCodec.encode(new Object[]{"user100"}, types);
        byte[] fatOther = IndexKeyCodec.encode(new Object[]{"user101"}, types);

        assertEquals(slim.length + 256, fat.length);
        assertEquals("user100", IndexKeyCodec.decode(fat, types)[0]);
        assertEquals(0, IndexKeyCodec.compare(fat, fat, types));
        assertTrue(IndexKeyCodec.compare(fat, fatOther, types) < 0);
    }
}
