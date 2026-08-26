package com.example.database.processor.analyser;

import java.util.Objects;

/**
 * Semantically valid CREATE DATABASE. Executor creates the directory.
 */
public final class AnalyzedCreateDatabase implements AnalyzedQuery {

    private final String database;

    public AnalyzedCreateDatabase(String database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public String database() {
        return database;
    }

    @Override
    public String toString() {
        return "AnalyzedCreateDatabase{database=" + database + "}";
    }
}
