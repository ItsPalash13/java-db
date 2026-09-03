package com.example.database.processor.executor.engine.volcano;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Extracts the lookup key from {@code col = literal} when the planner chose INDEX_SCAN.
 */
final class IndexEqualityHelper {

    private IndexEqualityHelper() {
    }

    static Optional<Object[]> lookupKey(
            Expression where,
            List<ColumnMetadata> columns,
            IndexMetadata index
    ) {
        if (where == null || index.columnIds().isEmpty()) {
            return Optional.empty();
        }
        Integer leadingColumnId = index.columnIds().get(0);
        String columnName = null;
        for (ColumnMetadata column : columns) {
            if (column.columnId().isPresent() && column.columnId().getAsInt() == leadingColumnId) {
                columnName = column.name();
                break;
            }
        }
        if (columnName == null) {
            return Optional.empty();
        }
        Object literal = equalityLiteral(where, columnName);
        if (literal == null) {
            return Optional.empty();
        }
        return Optional.of(new Object[]{literal});
    }

    private static Object equalityLiteral(Expression where, String columnName) {
        if (!(where instanceof BinaryExpression binary) || binary.operator() != TokenCatalog.EQ) {
            return null;
        }
        if (binary.left() instanceof ColumnExpression column && binary.right() instanceof LiteralExpression literal) {
            if (columnName.equalsIgnoreCase(column.name())) {
                return literal.value();
            }
        }
        if (binary.right() instanceof ColumnExpression column && binary.left() instanceof LiteralExpression literal) {
            if (columnName.equalsIgnoreCase(column.name())) {
                return literal.value();
            }
        }
        return null;
    }
}
