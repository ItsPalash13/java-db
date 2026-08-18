package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SELECT projections FROM table [WHERE condition].
 */
public final class SelectQuery implements Query {

    private final boolean star;
    private final List<Expression> projections;
    private final String table;
    private final Expression where;

    public SelectQuery(boolean star, List<Expression> projections, String table, Expression where) {
        this.star = star;
        this.projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        this.table = Objects.requireNonNull(table, "table");
        this.where = where;
    }

    public boolean star() {
        return star;
    }

    public List<Expression> projections() {
        return projections;
    }

    public String table() {
        return table;
    }

    public Optional<Expression> where() {
        return Optional.ofNullable(where);
    }

    @Override
    public String toString() {
        return "SelectQuery{star=" + star + ", projections=" + projections
                + ", table=" + table + ", where=" + where + "}";
    }
}
