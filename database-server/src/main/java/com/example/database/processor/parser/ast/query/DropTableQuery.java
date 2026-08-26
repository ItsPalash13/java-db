package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.Objects;

/**
 * DROP TABLE database.table.
 */
public final class DropTableQuery implements Query {

    private final QualifiedTable table;

    public DropTableQuery(QualifiedTable table) {
        // Table names are database.table; a bare String would allow DROP TABLE users.
        this.table = Objects.requireNonNull(table, "table");
    }

    public QualifiedTable table() {
        return table;
    }

    @Override
    public String toString() {
        return "DropTableQuery{table=" + table + "}";
    }
}
