package com.example.database.processor.executor;

import com.example.database.network.wire.WireMessage;
import com.example.database.processor.planner.DescribeTablePlan;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.processor.planner.ShowDatabasesPlan;
import com.example.database.processor.planner.ShowTablesPlan;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.TableMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only catalog introspection. No transaction, WAL, or lock — same as analyser reads.
 */
public final class DescribeExecutor implements QueryExecutor {

    private static final List<WireMessage.ResultSet.Column> DESCRIBE_COLUMNS = List.of(
            new WireMessage.ResultSet.Column("Field", "VARCHAR"),
            new WireMessage.ResultSet.Column("Type", "VARCHAR"),
            new WireMessage.ResultSet.Column("Null", "VARCHAR")
    );

    private static final List<WireMessage.ResultSet.Column> DATABASE_COLUMN = List.of(
            new WireMessage.ResultSet.Column("Database", "VARCHAR")
    );

    private static final List<WireMessage.ResultSet.Column> TABLE_COLUMN = List.of(
            new WireMessage.ResultSet.Column("Table", "VARCHAR")
    );

    private final CatalogManager catalogManager;

    public DescribeExecutor(CatalogManager catalogManager) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof DescribeTablePlan describe) {
            return describeTable(describe.database(), describe.table());
        }
        if (plan instanceof ShowDatabasesPlan) {
            return showDatabases();
        }
        if (plan instanceof ShowTablesPlan showTables) {
            return showTables(showTables.database());
        }
        throw new ExecutionException("DescribeExecutor cannot execute " + plan.queryType());
    }

    private QueryResult describeTable(String database, String table) {
        TableMetadata metadata = catalogManager.getTable(database, table).orElseThrow(
                () -> new ExecutionException("table does not exist: " + database + "." + table)
        );
        List<List<Object>> rows = new ArrayList<>();
        for (ColumnMetadata column : metadata.columns()) {
            rows.add(List.of(
                    column.name(),
                    column.type().name(),
                    column.nullable() ? "YES" : "NO"
            ));
        }
        return QueryResult.resultSet(DESCRIBE_COLUMNS, rows);
    }

    private QueryResult showDatabases() {
        List<List<Object>> rows = new ArrayList<>();
        for (String database : catalogManager.allDatabases()) {
            rows.add(List.of(database));
        }
        return QueryResult.resultSet(DATABASE_COLUMN, rows);
    }

    private QueryResult showTables(String database) {
        if (!catalogManager.databaseExists(database)) {
            throw new ExecutionException("database does not exist: " + database);
        }
        List<List<Object>> rows = new ArrayList<>();
        catalogManager.allTables().stream()
                .filter(table -> table.database().equals(database))
                .map(TableMetadata::name)
                .sorted(Comparator.naturalOrder())
                .forEach(name -> rows.add(List.of(name)));
        return QueryResult.resultSet(TABLE_COLUMN, rows);
    }
}
