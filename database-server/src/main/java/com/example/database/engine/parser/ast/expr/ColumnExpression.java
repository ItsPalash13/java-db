package com.example.database.engine.parser.ast.expr;

import com.example.database.engine.parser.ast.Expression;

import java.util.Objects;

/**
 * Column reference expression.
 */
public final class ColumnExpression implements Expression {

    private final String name;

    public ColumnExpression(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "Column(" + name + ")";
    }
}
