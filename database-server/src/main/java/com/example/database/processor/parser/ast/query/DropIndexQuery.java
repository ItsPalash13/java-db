package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

import java.util.Objects;

/**
 * DROP INDEX name.
 */
public final class DropIndexQuery implements Query {

    private final String index;

    public DropIndexQuery(String index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public String index() {
        return index;
    }

    @Override
    public String toString() {
        return "DropIndexQuery{index=" + index + "}";
    }
}
