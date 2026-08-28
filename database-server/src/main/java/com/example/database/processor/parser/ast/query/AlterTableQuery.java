package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.Objects;
import java.util.Optional;

/**
 * ALTER TABLE database.table ADD [COLUMN] name type | DROP [COLUMN] name.
 * ADD requires a column type; DROP stays parse-only until its execute phase.
 */
public final class AlterTableQuery implements Query {

    public enum Action {
        ADD_COLUMN,
        DROP_COLUMN
    }

    private final QualifiedTable table;
    private final Action action;
    private final String column;
    private final ColumnSqlType addColumnType;

    public AlterTableQuery(QualifiedTable table, Action action, String column, ColumnSqlType addColumnType) {
        this.table = Objects.requireNonNull(table, "table");
        this.action = Objects.requireNonNull(action, "action");
        this.column = Objects.requireNonNull(column, "column");
        if (action == Action.ADD_COLUMN) {
            this.addColumnType = Objects.requireNonNull(addColumnType, "addColumnType");
        } else {
            if (addColumnType != null) {
                throw new IllegalArgumentException("DROP COLUMN must not carry a column type");
            }
            this.addColumnType = null;
        }
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

    /** Present only for {@link Action#ADD_COLUMN}. */
    public Optional<ColumnSqlType> addColumnType() {
        return Optional.ofNullable(addColumnType);
    }

    @Override
    public String toString() {
        if (action == Action.ADD_COLUMN) {
            return "AlterTableQuery{table=" + table + ", action=" + action + ", column=" + column
                    + ", type=" + addColumnType + "}";
        }
        return "AlterTableQuery{table=" + table + ", action=" + action + ", column=" + column + "}";
    }
}
