package com.example.database.processor.analyser;

import java.util.Objects;

/**
 * Semantically valid DROP DATABASE. Executor removes the empty directory.
 */
public final class AnalyzedDropDatabase implements AnalyzedQuery {

    private final String database;

    public AnalyzedDropDatabase(String database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public String database() {
        return database;
    }

    @Override
    public String toString() {
        return "AnalyzedDropDatabase{database=" + database + "}";
    }
}
