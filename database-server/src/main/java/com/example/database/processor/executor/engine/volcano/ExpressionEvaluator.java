package com.example.database.processor.executor.engine.volcano;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;

import java.util.Map;
import java.util.Objects;

/**
 * Evaluates typed AST expressions against one {@link Tuple}. Analysis already
 * checked types; this only computes runtime values for Filter / UPDATE SET.
 */
public final class ExpressionEvaluator {

    private final Map<String, Integer> columnIdsByName;

    public ExpressionEvaluator(Map<String, Integer> columnIdsByName) {
        this.columnIdsByName = Map.copyOf(Objects.requireNonNull(columnIdsByName, "columnIdsByName"));
    }

    public Object evaluate(Expression expression, Tuple tuple) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(tuple, "tuple");
        if (expression instanceof LiteralExpression literal) {
            return coerceLiteral(literal.value());
        }
        if (expression instanceof ColumnExpression column) {
            Integer columnId = columnIdsByName.get(column.name());
            if (columnId == null) {
                throw new IllegalStateException("unknown column at execute: " + column.name());
            }
            return tuple.get(columnId);
        }
        if (expression instanceof BinaryExpression binary) {
            Object left = evaluate(binary.left(), tuple);
            Object right = evaluate(binary.right(), tuple);
            return compare(left, right, binary.operator());
        }
        throw new IllegalStateException("unsupported expression: " + expression);
    }

    public boolean matches(Expression where, Tuple tuple) {
        if (where == null) {
            return true;
        }
        Object result = evaluate(where, tuple);
        if (!(result instanceof Boolean bool)) {
            throw new IllegalStateException("WHERE did not evaluate to boolean");
        }
        return bool;
    }

    private static Object coerceLiteral(Object value) {
        if (value instanceof Long longValue) {
            return longValue.intValue();
        }
        return value;
    }

    private static boolean compare(Object left, Object right, TokenCatalog operator) {
        // SQL NULL: any comparison involving null is false (no three-valued logic yet).
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Boolean leftBool && right instanceof Boolean rightBool) {
            return switch (operator) {
                case EQ -> leftBool.equals(rightBool);
                case NEQ -> !leftBool.equals(rightBool);
                default -> throw new IllegalStateException("boolean comparison must be = or !=");
            };
        }
        if (left instanceof Integer leftInt && right instanceof Integer rightInt) {
            int cmp = Integer.compare(leftInt, rightInt);
            return matchCmp(cmp, operator);
        }
        if (left instanceof String leftStr && right instanceof String rightStr) {
            int cmp = leftStr.compareTo(rightStr);
            return matchCmp(cmp, operator);
        }
        throw new IllegalStateException("cannot compare " + left.getClass().getSimpleName()
                + " with " + right.getClass().getSimpleName());
    }

    private static boolean matchCmp(int cmp, TokenCatalog operator) {
        return switch (operator) {
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            default -> throw new IllegalStateException("unsupported comparison: " + operator);
        };
    }
}
