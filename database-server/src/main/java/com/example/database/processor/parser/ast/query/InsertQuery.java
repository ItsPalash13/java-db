package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;

/**
 * INSERT INTO database.table [(columns)] VALUES (values).
 */
public final class InsertQuery implements Query {

    private final QualifiedTable table;
    private final List<String> columns;
    private final List<Expression> values;

    public InsertQuery(QualifiedTable table, List<String> columns, List<Expression> values) {
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    public QualifiedTable table() {
        return table;
    }

    /** Empty when column list was omitted. */
    public List<String> columns() {
        return columns;
    }

    public List<Expression> values() {
        return values;
    }

    @Override
    public String toString() {
        return "InsertQuery{table=" + table + ", columns=" + columns + ", values=" + values + "}";
    }
}
