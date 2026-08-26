package com.example.database.processor.parser.ast;

import java.util.Objects;

/**
 * One column in {@code CREATE TABLE name (col type, ...)}.
 */
public final class ColumnDefinition {

    private final String name;
    private final ColumnSqlType type;

    public ColumnDefinition(String name, ColumnSqlType type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String name() {
        return name;
    }

    public ColumnSqlType type() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnDefinition that)) {
            return false;
        }
        return name.equals(that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return name + " " + type;
    }
}
