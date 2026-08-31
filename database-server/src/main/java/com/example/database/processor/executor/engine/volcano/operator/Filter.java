package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.ExpressionEvaluator;
import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.processor.parser.ast.Expression;

import java.util.Objects;

/**
 * Keeps child tuples that satisfy WHERE. Null WHERE means pass-through.
 */
public final class Filter implements VolcanoOperator {

    private final VolcanoOperator child;
    private final Expression where;
    private final ExpressionEvaluator evaluator;

    public Filter(VolcanoOperator child, Expression where, ExpressionEvaluator evaluator) {
        this.child = Objects.requireNonNull(child, "child");
        this.where = where;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        Tuple tuple;
        while ((tuple = child.next()) != null) {
            if (evaluator.matches(where, tuple)) {
                return tuple;
            }
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }
}
