package com.example.database.engine.parser.ast.expr;

import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ast.Expression;

import java.util.Objects;

/**
 * Binary comparison: left op right.
 */
public final class BinaryExpression implements Expression {

    private final Expression left;
    private final TokenCatalog operator;
    private final Expression right;

    public BinaryExpression(Expression left, TokenCatalog operator, Expression right) {
        this.left = Objects.requireNonNull(left, "left");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.right = Objects.requireNonNull(right, "right");
    }

    public Expression left() {
        return left;
    }

    public TokenCatalog operator() {
        return operator;
    }

    public Expression right() {
        return right;
    }

    @Override
    public String toString() {
        return "Binary(" + left + " " + operator + " " + right + ")";
    }
}
