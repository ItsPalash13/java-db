package com.example.database.processor.parser.ast;

import java.util.Objects;

/**
 * One column in {@code CREATE TABLE name (col type [PRIMARY KEY], ...)}.
 * {@link #primaryKey()} is true when the column was declared with {@code PRIMARY KEY}.
 */
public final class ColumnDefinition {

    private final String name;
    private final ColumnSqlType type;
    private final boolean primaryKey;

    public ColumnDefinition(String name, ColumnSqlType type) {
        this(name, type, false);
    }

    public ColumnDefinition(String name, ColumnSqlType type, boolean primaryKey) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.primaryKey = primaryKey;
    }

    public String name() {
        return name;
    }

    public ColumnSqlType type() {
        return type;
    }

    /** True when the column was declared with {@code PRIMARY KEY}. */
    public boolean primaryKey() {
        return primaryKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnDefinition that)) {
            return false;
        }
        return name.equals(that.name) && type == that.type && primaryKey == that.primaryKey;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, primaryKey);
    }

    @Override
    public String toString() {
        return name + " " + type + (primaryKey ? " PRIMARY KEY" : "");
    }
}
