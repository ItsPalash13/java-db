package com.example.database.engine.parser.ast.query;

import com.example.database.engine.parser.ast.Query;

import java.util.Objects;

/**
 * CREATE DATABASE name.
 */
public final class CreateDatabaseQuery implements Query {

    private final String name;

    public CreateDatabaseQuery(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "CreateDatabaseQuery{name=" + name + "}";
    }
}
