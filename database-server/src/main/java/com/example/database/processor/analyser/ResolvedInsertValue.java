package com.example.database.processor.analyser;

import com.example.database.storage.catalog.ColumnType;

import java.util.Objects;

/**
 * One INSERT slot in catalog column order. {@code value} is null only for omitted
 * nullable columns — there is no NULL token yet, so analysis synthesizes it.
 */
public record ResolvedInsertValue(int columnId, ColumnType type, Object value) {

    public ResolvedInsertValue {
        Objects.requireNonNull(type, "type");
        if (columnId < 1) {
            throw new IllegalArgumentException("columnId must be >= 1");
        }
    }
}
