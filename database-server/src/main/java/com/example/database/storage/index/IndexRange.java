package com.example.database.storage.index;

import java.util.Objects;

/**
 * Bounds for {@link IndexStore#lookupRange}. {@code null} low or high means unbounded on that side.
 * {@code prefixColumns} limits compare to the first N key components (composite leading prefix).
 */
public record IndexRange(
        Object[] lowKey,
        boolean lowInclusive,
        Object[] highKey,
        boolean highInclusive,
        int prefixColumns
) {
    public IndexRange {
        if (prefixColumns < 1) {
            throw new IllegalArgumentException("prefixColumns must be >= 1");
        }
        lowKey = lowKey == null ? null : lowKey.clone();
        highKey = highKey == null ? null : highKey.clone();
    }

    public static IndexRange equality(Object[] key, int prefixColumns) {
        Objects.requireNonNull(key, "key");
        return new IndexRange(key, true, key, true, prefixColumns);
    }
}
