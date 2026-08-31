package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.analyser.ResolvedProjection;
import com.example.database.processor.executor.engine.volcano.Tuple;

import java.util.List;
import java.util.Objects;

/**
 * Builds an output tuple whose slots follow the SELECT list (columns and literals).
 * Output columnIds are 1..n in projection order for the result set encoder.
 */
public final class Project implements VolcanoOperator {

    private final VolcanoOperator child;
    private final List<ResolvedProjection> projections;

    public Project(VolcanoOperator child, List<ResolvedProjection> projections) {
        this.child = Objects.requireNonNull(child, "child");
        this.projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        Tuple source = child.next();
        if (source == null) {
            return null;
        }
        Object[] out = new Object[projections.size()];
        for (int i = 0; i < projections.size(); i++) {
            ResolvedProjection projection = projections.get(i);
            if (projection.isLiteral()) {
                out[i] = projection.literalValue().orElseThrow();
            } else {
                out[i] = source.get(projection.columnId().orElseThrow());
            }
        }
        return new Tuple(source.rowId(), out);
    }

    @Override
    public void close() {
        child.close();
    }
}
