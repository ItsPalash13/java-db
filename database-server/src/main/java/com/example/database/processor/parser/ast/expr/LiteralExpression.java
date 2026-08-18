package com.example.database.processor.parser.ast.expr;

import com.example.database.processor.parser.ast.Expression;

import java.util.Objects;

/**
 * Literal value (string, number, or boolean).
 */
public final class LiteralExpression implements Expression {

    private final Object value;

    public LiteralExpression(Object value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public Object value() {
        return value;
    }

    @Override
    public String toString() {
        return "Literal(" + value + ")";
    }
}
