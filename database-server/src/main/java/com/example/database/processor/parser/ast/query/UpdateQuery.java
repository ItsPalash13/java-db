package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Assignment;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * UPDATE database.table SET assignments [WHERE condition].
 */
public final class UpdateQuery implements Query {

    private final QualifiedTable table;
    private final List<Assignment> assignments;
    private final Expression where;

    public UpdateQuery(QualifiedTable table, List<Assignment> assignments, Expression where) {
        this.table = Objects.requireNonNull(table, "table");
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        this.where = where;
    }

    public QualifiedTable table() {
        return table;
    }

    public List<Assignment> assignments() {
        return assignments;
    }

    public Optional<Expression> where() {
        return Optional.ofNullable(where);
    }

    @Override
    public String toString() {
        return "UpdateQuery{table=" + table + ", assignments=" + assignments + ", where=" + where + "}";
    }
}
