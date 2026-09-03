package com.example.database.processor.planner;

import java.util.Arrays;
import java.util.Objects;

/**
 * Index probe bounds attached to a plan when {@link AccessPath#indexScan} is chosen.
 * {@code prefixColumns} is how many leading index key components participate in compare
 * (1 for single-column index or leading-column-only predicate on a composite index).
 */
public final class IndexScanSpec {

    private final String indexName;
    private final Object[] lowKey;
    private final boolean lowInclusive;
    private final Object[] highKey;
    private final boolean highInclusive;
    private final int prefixColumns;
    private final boolean equality;

    private IndexScanSpec(
            String indexName,
            Object[] lowKey,
            boolean lowInclusive,
            Object[] highKey,
            boolean highInclusive,
            int prefixColumns,
            boolean equality
    ) {
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        this.lowKey = lowKey;
        this.highKey = highKey;
        this.lowInclusive = lowInclusive;
        this.highInclusive = highInclusive;
        if (prefixColumns < 1) {
            throw new IllegalArgumentException("prefixColumns must be >= 1");
        }
        this.prefixColumns = prefixColumns;
        this.equality = equality;
    }

    public static IndexScanSpec equality(String indexName, Object[] key, int prefixColumns) {
        Objects.requireNonNull(key, "key");
        return new IndexScanSpec(indexName, key.clone(), true, key.clone(), true, prefixColumns, true);
    }

    public static IndexScanSpec range(
            String indexName,
            Object[] lowKey,
            boolean lowInclusive,
            Object[] highKey,
            boolean highInclusive,
            int prefixColumns
    ) {
        return new IndexScanSpec(
                indexName,
                lowKey == null ? null : lowKey.clone(),
                lowInclusive,
                highKey == null ? null : highKey.clone(),
                highInclusive,
                prefixColumns,
                false
        );
    }

    public String indexName() {
        return indexName;
    }

    public Object[] lowKey() {
        return lowKey == null ? null : lowKey.clone();
    }

    public boolean lowInclusive() {
        return lowInclusive;
    }

    public Object[] highKey() {
        return highKey == null ? null : highKey.clone();
    }

    public boolean highInclusive() {
        return highInclusive;
    }

    public int prefixColumns() {
        return prefixColumns;
    }

    public boolean equality() {
        return equality;
    }

    @Override
    public String toString() {
        return "IndexScanSpec{index=" + indexName
                + ", equality=" + equality
                + ", prefixColumns=" + prefixColumns
                + ", low=" + Arrays.toString(lowKey)
                + ", lowInclusive=" + lowInclusive
                + ", high=" + Arrays.toString(highKey)
                + ", highInclusive=" + highInclusive + "}";
    }
}
