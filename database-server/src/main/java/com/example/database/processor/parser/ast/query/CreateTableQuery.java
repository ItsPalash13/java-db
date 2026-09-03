package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CREATE TABLE database.table (column type [PRIMARY KEY], ...).
 * At most one column may be declared PRIMARY KEY; {@link #primaryKeyColumn()} carries its name.
 */
public final class CreateTableQuery implements Query {

    private final QualifiedTable table;
    private final List<ColumnDefinition> columns;
    private final Optional<String> primaryKeyColumn;

    public CreateTableQuery(QualifiedTable table, List<ColumnDefinition> columns) {
        this.table = Objects.requireNonNull(table, "table");
        // copyOf so callers cannot mutate the AST after parse/analyse.
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        // Derive PK column from definitions; only one allowed.
        String pk = null;
        for (ColumnDefinition col : this.columns) {
            if (col.primaryKey()) {
                if (pk != null) {
                    throw new IllegalArgumentException("only one PRIMARY KEY column allowed");
                }
                pk = col.name();
            }
        }
        this.primaryKeyColumn = Optional.ofNullable(pk);
    }

    public QualifiedTable table() {
        return table;
    }

    public List<ColumnDefinition> columns() {
        return columns;
    }

    /** Column name declared as PRIMARY KEY, or empty if none. */
    public Optional<String> primaryKeyColumn() {
        return primaryKeyColumn;
    }

    @Override
    public String toString() {
        return "CreateTableQuery{table=" + table + ", columns=" + columns
                + ", primaryKeyColumn=" + primaryKeyColumn + "}";
    }
}
