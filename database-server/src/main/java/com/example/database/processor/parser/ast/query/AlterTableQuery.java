package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.Objects;

/**
 * ALTER TABLE database.table ADD|DROP [COLUMN] column.
 */
public final class AlterTableQuery implements Query {

    public enum Action {
        ADD_COLUMN,
        DROP_COLUMN
    }

    private final QualifiedTable table;
    private final Action action;
    private final String column;

    public AlterTableQuery(QualifiedTable table, Action action, String column) {
        this.table = Objects.requireNonNull(table, "table");
        this.action = Objects.requireNonNull(action, "action");
        this.column = Objects.requireNonNull(column, "column");
    }

    public QualifiedTable table() {
        return table;
    }

    public Action action() {
        return action;
    }

    public String column() {
        return column;
    }

    @Override
    public String toString() {
        return "AlterTableQuery{table=" + table + ", action=" + action + ", column=" + column + "}";
    }
}
