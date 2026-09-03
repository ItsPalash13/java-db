package com.example.database.processor.planner;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Table scan vs index scan from catalog metadata already copied onto the analyzed query.
 * Equality and one-sided ranges on leading indexed column(s) select INDEX_SCAN.
 * When multiple indexes match, a scoring heuristic picks the best one:
 * equality beats range, unique/PK beats non-unique, more prefix columns beat fewer,
 * narrower key breaks ties.
 */
final class AccessPathChooser {

    private AccessPathChooser() {
    }

    static AccessPathChoice choose(Expression where, List<ColumnMetadata> columns, List<IndexMetadata> indexes) {
        if (where == null || indexes.isEmpty()) {
            return AccessPathChoice.tableScan();
        }
        AccessPathChoice best = null;
        int bestScore = -1;
        int bestWidth = Integer.MAX_VALUE;
        for (IndexMetadata index : indexes) {
            AccessPathChoice match = matchIndex(where, columns, index);
            if (match == null) {
                continue;
            }
            int score = score(match, index);
            int width = index.columnIds().size();
            // Higher score wins; on tie, narrower key (fewer columns) wins; on tie, catalog order.
            if (score > bestScore || (score == bestScore && width < bestWidth)) {
                best = match;
                bestScore = score;
                bestWidth = width;
            }
        }
        return best != null ? best : AccessPathChoice.tableScan();
    }

    /**
     * Heuristic score: equality > range, unique/PK preferred, more prefix columns better.
     * Not a cost model — just a teaching-quality preference ordering.
     */
    private static int score(AccessPathChoice choice, IndexMetadata index) {
        int s = 0;
        if (choice.indexScanSpec() != null && choice.indexScanSpec().equality()) {
            s += 10;
        }
        if (index.unique()) {
            s += 5;
        }
        if (choice.indexScanSpec() != null) {
            s += choice.indexScanSpec().prefixColumns();
        }
        return s;
    }

    private static AccessPathChoice matchIndex(
            Expression where,
            List<ColumnMetadata> columns,
            IndexMetadata index
    ) {
        if (index.columnIds().isEmpty()) {
            return null;
        }
        Integer leadingColumnId = index.columnIds().get(0);
        String leadingColumnName = columnName(columns, leadingColumnId);
        if (leadingColumnName == null) {
            return null;
        }
        EqualityPrefix equalityPrefix = equalityPrefix(where, columns, index);
        if (equalityPrefix != null) {
            return AccessPathChoice.indexScan(
                    IndexScanSpec.equality(index.name(), equalityPrefix.keyValues(), equalityPrefix.prefixColumns())
            );
        }
        RangePredicate range = rangeOnColumn(where, leadingColumnName);
        if (range != null) {
            return AccessPathChoice.indexScan(
                    IndexScanSpec.range(
                            index.name(),
                            range.lowKey(),
                            range.lowInclusive(),
                            range.highKey(),
                            range.highInclusive(),
                            1
                    )
            );
        }
        return null;
    }

    /**
     * Longest leading-column equality prefix, e.g. {@code a = 1} on index {@code (a,b)}.
     */
    private static EqualityPrefix equalityPrefix(
            Expression where,
            List<ColumnMetadata> columns,
            IndexMetadata index
    ) {
        if (!(where instanceof BinaryExpression binary) || binary.operator() != TokenCatalog.EQ) {
            return null;
        }
        String columnName = equalityColumnName(binary);
        Object literal = equalityLiteral(binary);
        if (columnName == null || literal == null) {
            return null;
        }
        int matchIndex = -1;
        for (int i = 0; i < index.columnIds().size(); i++) {
            Integer columnId = index.columnIds().get(i);
            String name = columnName(columns, columnId);
            if (name != null && name.equalsIgnoreCase(columnName)) {
                matchIndex = i;
                break;
            }
        }
        if (matchIndex != 0) {
            // Only leading-column (or full leading prefix) equality is sargable today.
            return matchIndex == 0 ? new EqualityPrefix(new Object[]{literal}, 1) : null;
        }
        return new EqualityPrefix(new Object[]{literal}, 1);
    }

    private static RangePredicate rangeOnColumn(Expression where, String columnName) {
        if (!(where instanceof BinaryExpression binary)) {
            return null;
        }
        String predicateColumn = null;
        Object literal = null;
        boolean columnOnLeft = binary.left() instanceof ColumnExpression;
        if (columnOnLeft && binary.right() instanceof LiteralExpression literalExpression) {
            predicateColumn = ((ColumnExpression) binary.left()).name();
            literal = literalExpression.value();
        } else if (binary.right() instanceof ColumnExpression column
                && binary.left() instanceof LiteralExpression literalExpression) {
            predicateColumn = column.name();
            literal = literalExpression.value();
        }
        if (predicateColumn == null || !predicateColumn.equalsIgnoreCase(columnName)) {
            return null;
        }
        return switch (binary.operator()) {
            case GT -> new RangePredicate(new Object[]{literal}, false, null, false);
            case GTE -> new RangePredicate(new Object[]{literal}, true, null, false);
            case LT -> new RangePredicate(null, false, new Object[]{literal}, false);
            case LTE -> new RangePredicate(null, false, new Object[]{literal}, true);
            default -> null;
        };
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

    private static Object equalityLiteral(BinaryExpression binary) {
        if (binary.right() instanceof LiteralExpression literal) {
            return literal.value();
        }
        if (binary.left() instanceof LiteralExpression literal) {
            return literal.value();
        }
        return null;
    }

    private static String columnName(List<ColumnMetadata> columns, int columnId) {
        for (ColumnMetadata column : columns) {
            if (column.columnId().isPresent() && column.columnId().getAsInt() == columnId) {
                return column.name();
            }
        }
        return null;
    }

    private record EqualityPrefix(Object[] keyValues, int prefixColumns) {
    }

    private record RangePredicate(
            Object[] lowKey,
            boolean lowInclusive,
            Object[] highKey,
            boolean highInclusive
    ) {
    }
}
