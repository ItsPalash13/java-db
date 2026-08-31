package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.Tuple;

/**
 * Volcano pull iterator: open → next (one tuple or null) → close.
 * Compiles from declarative plans inside VolcanoExecutor — not an ExecutionPlan.
 */
public interface VolcanoOperator {

    void open();

    /** Next tuple, or {@code null} when exhausted. */
    Tuple next();

    void close();
}
