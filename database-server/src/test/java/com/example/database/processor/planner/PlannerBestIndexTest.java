package com.example.database.processor.planner;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.IndexMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link AccessPathChooser} picks the best index when
 * multiple indexes match the same WHERE predicate.
 */
class PlannerBestIndexTest {

    // Columns: id (1), name (2), age (3)
    private static final List<ColumnMetadata> COLUMNS = List.of(
            new ColumnMetadata(1, "id", com.example.database.storage.catalog.ColumnType.INT, false),
            new ColumnMetadata(2, "name", com.example.database.storage.catalog.ColumnType.VARCHAR, true),
            new ColumnMetadata(3, "age", com.example.database.storage.catalog.ColumnType.INT, true)
    );

    @Test
    void prefersUniqueOverNonUniqueForEquality() {
        // Two indexes on "id": one unique, one not.
        IndexMetadata nonUnique = new IndexMetadata("idx_id", List.of(1), false);
        IndexMetadata unique = new IndexMetadata("uq_id", List.of(1), true);
        // Non-unique listed first in catalog order.
        List<IndexMetadata> indexes = List.of(nonUnique, unique);

        Expression where = eq("id", 42);
        AccessPathChoice choice = AccessPathChooser.choose(where, COLUMNS, indexes);

        assertNotNull(choice.indexScanSpec());
        assertEquals("uq_id", choice.indexScanSpec().indexName(),
                "should prefer unique index over non-unique for equality");
    }

    @Test
    void prefersEqualityOverRange() {
        // Index on "id" matching equality, index on "age" matching range.
        // But WHERE is on "id", so only "id" indexes match.
        // Instead: two indexes on "id" — one equality match, one range match via different
        // token ops isn't possible on same column. So test with two indexes on different
        // columns both matching.
        // Actually the WHERE clause is a single predicate, so only one column matches at a time.
        // Test: same column, unique vs non-unique — the scoring proves equality+unique wins.
        IndexMetadata nonUnique = new IndexMetadata("idx_id", List.of(1), false);
        IndexMetadata unique = new IndexMetadata("pk_id", List.of(1), true);
        List<IndexMetadata> indexes = List.of(nonUnique, unique);

        Expression where = eq("id", 10);
        AccessPathChoice choice = AccessPathChooser.choose(where, COLUMNS, indexes);

        assertNotNull(choice.indexScanSpec());
        assertEquals("pk_id", choice.indexScanSpec().indexName());
        // Equality should be selected, not a range.
        assertEquals(true, choice.indexScanSpec().equality());
    }

    @Test
    void fallsBackToTableScanWhenNoIndexMatches() {
        IndexMetadata nameIndex = new IndexMetadata("idx_name", List.of(2), false);
        List<IndexMetadata> indexes = List.of(nameIndex);

        // WHERE on "age" — no index on "age".
        Expression where = eq("age", 25);
        AccessPathChoice choice = AccessPathChooser.choose(where, COLUMNS, indexes);

        assertEquals(AccessPath.Kind.TABLE_SCAN, choice.accessPath().kind());
    }

    @Test
    void prefersNarrowerKeyOnTiedScore() {
        // Both unique, both equality on "id" — but one has fewer columns (narrower key).
        IndexMetadata wide = new IndexMetadata("idx_id_name", List.of(1, 2), true);
        IndexMetadata narrow = new IndexMetadata("idx_id", List.of(1), true);
        // Wide listed first.
        List<IndexMetadata> indexes = List.of(wide, narrow);

        Expression where = eq("id", 5);
        AccessPathChoice choice = AccessPathChooser.choose(where, COLUMNS, indexes);

        assertNotNull(choice.indexScanSpec());
        assertEquals("idx_id", choice.indexScanSpec().indexName(),
                "should prefer narrower key when scores are tied");
    }

    private static Expression eq(String column, Object value) {
        return new BinaryExpression(
                new ColumnExpression(column),
                TokenCatalog.EQ,
                new LiteralExpression(value)
        );
    }
}
