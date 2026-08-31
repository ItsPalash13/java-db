package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.storage.catalog.ColumnType;

import java.util.Objects;

/**
 * One UPDATE SET target after the column name is bound to a catalog id.
 * The expression is still the AST; Volcano evaluates it later.
 */
public record ResolvedAssignment(int columnId, ColumnType type, Expression value) {

    public ResolvedAssignment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        if (columnId < 1) {
            throw new IllegalArgumentException("columnId must be >= 1");
        }
    }
}
