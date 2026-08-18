package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.Query;

import java.util.Objects;
import java.util.Optional;

/**
 * DELETE FROM table [WHERE condition].
 */
public final class DeleteQuery implements Query {

    private final String table;
    private final Expression where;

    public DeleteQuery(String table, Expression where) {
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
    }

    public String table() {
        return table;
    }

    public Optional<Expression> where() {
        return Optional.ofNullable(where);
    }

    @Override
    public String toString() {
        return "DeleteQuery{table=" + table + ", where=" + where + "}";
    }
}
