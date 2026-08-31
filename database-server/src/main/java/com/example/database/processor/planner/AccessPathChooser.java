package com.example.database.processor.planner;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.List;

/**
 * Table scan vs index scan from catalog metadata already copied onto the analyzed query.
 * Equality on an indexed leading column is the only path that selects INDEX_SCAN.
 */
final class AccessPathChooser {

    private AccessPathChooser() {
    }

    static AccessPath choose(Expression where, List<ColumnMetadata> columns, List<IndexMetadata> indexes) {
        Integer columnId = equalityColumnId(where, columns);
        if (columnId == null) {
            return AccessPath.tableScan();
        }
        for (IndexMetadata index : indexes) {
            if (!index.columnIds().isEmpty() && index.columnIds().get(0).equals(columnId)) {
                return AccessPath.indexScan(index.name());
            }
        }
        return AccessPath.tableScan();
    }

    /**
     * Only {@code col = literal} or {@code literal = col}. Chained comparisons and ranges
     * stay table scans — a later optimizer can widen this without changing analysis.
     */
    private static Integer equalityColumnId(Expression where, List<ColumnMetadata> columns) {
        if (!(where instanceof BinaryExpression binary) || binary.operator() != TokenCatalog.EQ) {
            return null;
        }
        String columnName = equalityColumnName(binary);
        if (columnName == null) {
            return null;
        }
        for (ColumnMetadata column : columns) {
            if (column.name().equals(columnName)) {
                return column.columnId().orElseThrow();
            }
        }
        return null;
    }

    private static String equalityColumnName(BinaryExpression binary) {
        if (binary.left() instanceof ColumnExpression column && binary.right() instanceof LiteralExpression) {
            return column.name();
        }
        if (binary.right() instanceof ColumnExpression column && binary.left() instanceof LiteralExpression) {
            return column.name();
        }
        return null;
    }
}
