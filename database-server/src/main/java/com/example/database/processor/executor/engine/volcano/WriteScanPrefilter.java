package com.example.database.processor.executor.engine.volcano;

import com.example.database.processor.executor.engine.volcano.operator.Filter;
import com.example.database.processor.executor.engine.volcano.operator.SeqScan;
import com.example.database.processor.executor.engine.volcano.operator.VolcanoOperator;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.table.TableStore;

import java.util.List;

/**
 * Builds the heap scan for UPDATE/DELETE. Only {@code id = <literal>} may use pre-lock
 * {@link Filter}; other WHERE clauses must lock each row before evaluating the predicate.
 */
final class WriteScanPrefilter {

    private WriteScanPrefilter() {
    }

    static VolcanoOperator scan(
            TableStore tableStore,
            String database,
            String table,
            Expression where,
            List<ColumnMetadata> columns,
            ExpressionEvaluator evaluator
    ) {
        VolcanoOperator scan = new SeqScan(tableStore, database, table);
        if (where != null && isIdColumnEquality(where, columns)) {
            scan = new Filter(scan, where, evaluator);
        }
        return scan;
    }

    private static boolean isIdColumnEquality(Expression where, List<ColumnMetadata> columns) {
        if (!(where instanceof BinaryExpression binary)) {
            return false;
        }
        if (binary.operator() != TokenCatalog.EQ) {
            return false;
        }
        String idColumn = null;
        for (ColumnMetadata column : columns) {
            if ("id".equalsIgnoreCase(column.name())) {
                idColumn = column.name();
                break;
            }
        }
        if (idColumn == null) {
            return false;
        }
        return matchesIdLiteral(binary.left(), binary.right(), idColumn)
                || matchesIdLiteral(binary.right(), binary.left(), idColumn);
    }

    private static boolean matchesIdLiteral(Expression columnSide, Expression literalSide, String idColumn) {
        return columnSide instanceof ColumnExpression col
                && idColumn.equalsIgnoreCase(col.name())
                && literalSide instanceof LiteralExpression;
    }
}
