package com.example.database.processor.executor.engine.volcano;

import java.util.Arrays;
import java.util.Objects;

/**
 * One in-memory row. Values are indexed by catalog columnId minus one
 * (columnId 1 → values[0]). rowId is assigned by {@code TableStore} on insert
 * so UPDATE/DELETE can target a specific heap slot without scanning by value.
 */
public final class Tuple {

    private final long rowId;
    private final Object[] values;

    public Tuple(long rowId, Object[] values) {
        this.rowId = rowId;
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    public long rowId() {
        return rowId;
    }

    public Object[] values() {
        return values.clone();
    }

    public Object get(int columnId) {
        if (columnId < 1 || columnId > values.length) {
            throw new IllegalArgumentException("columnId out of range: " + columnId);
        }
        return values[columnId - 1];
    }

    public Tuple withValues(Object[] newValues) {
        return new Tuple(rowId, newValues);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Tuple that)) {
            return false;
        }
        return rowId == that.rowId && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowId, Arrays.hashCode(values));
    }

    @Override
    public String toString() {
        return "Tuple{rowId=" + rowId + ", values=" + Arrays.toString(values) + "}";
    }
}
