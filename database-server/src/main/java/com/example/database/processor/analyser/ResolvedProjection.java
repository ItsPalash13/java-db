package com.example.database.processor.analyser;

import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One SELECT list item after name resolution. Either a catalog column (Volcano reads
 * that slot) or a typed literal (no column id — the constant is the value).
 */
public final class ResolvedProjection {

    private final OptionalInt columnId;
    private final String name;
    private final ColumnType type;
    private final Object literalValue;

    public static ResolvedProjection column(ColumnMetadata column) {
        Objects.requireNonNull(column, "column");
        return new ResolvedProjection(
                column.columnId().orElseThrow(() -> new IllegalArgumentException("column id required")),
                column.name(),
                column.type(),
                null
        );
    }

    public static ResolvedProjection literal(ColumnType type, Object value) {
        return new ResolvedProjection(OptionalInt.empty(), null, type, Objects.requireNonNull(value, "value"));
    }

    private ResolvedProjection(int columnId, String name, ColumnType type, Object literalValue) {
        this(OptionalInt.of(columnId), name, type, literalValue);
    }

    private ResolvedProjection(OptionalInt columnId, String name, ColumnType type, Object literalValue) {
        this.columnId = Objects.requireNonNull(columnId, "columnId");
        this.name = name;
        this.type = Objects.requireNonNull(type, "type");
        this.literalValue = literalValue;
    }

    public boolean isLiteral() {
        return columnId.isEmpty();
    }

    public OptionalInt columnId() {
        return columnId;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public ColumnType type() {
        return type;
    }

    public Optional<Object> literalValue() {
        return Optional.ofNullable(literalValue);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResolvedProjection that)) {
            return false;
        }
        return columnId.equals(that.columnId)
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(literalValue, that.literalValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnId, name, type, literalValue);
    }

    @Override
    public String toString() {
        if (isLiteral()) {
            return "ResolvedProjection{literal=" + literalValue + ", type=" + type + "}";
        }
        return "ResolvedProjection{columnId=" + columnId + ", name=" + name + ", type=" + type + "}";
    }
}
