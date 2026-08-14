package com.example.database.engine.parser.ast.query;

import com.example.database.engine.parser.ast.Query;

import java.util.Objects;

/**
 * DROP DATABASE name.
 */
public final class DropDatabaseQuery implements Query {

    private final String name;

    public DropDatabaseQuery(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "DropDatabaseQuery{name=" + name + "}";
    }
}
