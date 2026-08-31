package com.example.database.processor.planner;

import java.util.Objects;

/**
 * How the later row executor should read the table. INDEX_SCAN is a label from
 * catalog index definitions; there is no B+Tree yet.
 */
public final class AccessPath {

    public enum Kind {
        TABLE_SCAN,
        INDEX_SCAN
    }

    private final Kind kind;
    private final String indexName;

    public static AccessPath tableScan() {
        return new AccessPath(Kind.TABLE_SCAN, null);
    }

    public static AccessPath indexScan(String indexName) {
        return new AccessPath(Kind.INDEX_SCAN, Objects.requireNonNull(indexName, "indexName"));
    }

    private AccessPath(Kind kind, String indexName) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.indexName = indexName;
        if (kind == Kind.INDEX_SCAN && (indexName == null || indexName.isBlank())) {
            throw new IllegalArgumentException("index scan requires an index name");
        }
    }

    public Kind kind() {
        return kind;
    }

    public String indexName() {
        return indexName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessPath that)) {
            return false;
        }
        return kind == that.kind && Objects.equals(indexName, that.indexName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, indexName);
    }

    @Override
    public String toString() {
        if (kind == Kind.TABLE_SCAN) {
            return "AccessPath{TABLE_SCAN}";
        }
        return "AccessPath{INDEX_SCAN, index=" + indexName + "}";
    }
}
