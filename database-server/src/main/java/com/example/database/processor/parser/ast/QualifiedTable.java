package com.example.database.processor.parser.ast;

import java.util.Objects;

/**
 * Table reference as {@code database.table}. Unqualified names are rejected at parse time.
 */
public final class QualifiedTable {

    private final String database;
    private final String table;

    public QualifiedTable(String database, String table) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
        if (database.isBlank() || table.isBlank()) {
            throw new IllegalArgumentException("database and table must not be blank");
        }
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    /** Display form used in errors and logs: {@code shop.users}. */
    public String qualifiedName() {
        return database + "." + table;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QualifiedTable that)) {
            return false;
        }
        return database.equals(that.database) && table.equals(that.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, table);
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
