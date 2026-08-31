package com.example.database.processor.executor.engine.volcano;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator(Map.of("id", 1, "name", 2));

    @Test
    void comparesIntsAndStringsAndTreatsNullAsFalse() {
        Tuple row = new Tuple(1L, new Object[]{1, "Ada"});

        assertTrue(evaluator.matches(
                new BinaryExpression(new ColumnExpression("id"), TokenCatalog.EQ, new LiteralExpression(1L)),
                row
        ));
        assertFalse(evaluator.matches(
                new BinaryExpression(new ColumnExpression("id"), TokenCatalog.GT, new LiteralExpression(1L)),
                row
        ));
        assertTrue(evaluator.matches(
                new BinaryExpression(new ColumnExpression("name"), TokenCatalog.EQ, new LiteralExpression("Ada")),
                row
        ));

        Tuple withNull = new Tuple(2L, new Object[]{null, "Ada"});
        assertFalse(evaluator.matches(
                new BinaryExpression(new ColumnExpression("id"), TokenCatalog.EQ, new LiteralExpression(1L)),
                withNull
        ));
        assertEquals(1, evaluator.evaluate(new ColumnExpression("id"), row));
    }
}
