package com.example.database.processor.planner;

/**
 * Planner output for table vs index access, including optional index bounds when
 * {@link AccessPath#kind()} is {@link AccessPath.Kind#INDEX_SCAN}.
 */
public record AccessPathChoice(AccessPath accessPath, IndexScanSpec indexScanSpec) {

    public static AccessPathChoice tableScan() {
        return new AccessPathChoice(AccessPath.tableScan(), null);
    }

    public static AccessPathChoice indexScan(IndexScanSpec spec) {
        return new AccessPathChoice(AccessPath.indexScan(spec.indexName()), spec);
    }
}
