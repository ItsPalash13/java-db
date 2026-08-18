package com.example.database.processor.parser.ast;

import java.util.Objects;

/**
 * One UPDATE SET assignment: column = value.
 */
public final class Assignment {

    private final String column;
    private final Expression value;

    public Assignment(String column, Expression value) {
        this.column = Objects.requireNonNull(column, "column");
        this.value = Objects.requireNonNull(value, "value");
    }

    public String column() {
        return column;
    }

    public Expression value() {
        return value;
    }

    @Override
    public String toString() {
        return column + "=" + value;
    }
}
