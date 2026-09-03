package com.example.database.processor.analyser;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.Assignment;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.expr.BinaryExpression;
import com.example.database.processor.parser.ast.expr.ColumnExpression;
import com.example.database.processor.parser.ast.expr.LiteralExpression;
import com.example.database.processor.parser.ast.query.AlterTableQuery;
import com.example.database.processor.parser.ast.query.BeginQuery;
import com.example.database.processor.parser.ast.query.CheckpointQuery;
import com.example.database.processor.parser.ast.query.CommitQuery;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DeleteQuery;
import com.example.database.processor.parser.ast.query.DescribeTableQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.DropIndexQuery;
import com.example.database.processor.parser.ast.query.DropTableQuery;
import com.example.database.processor.parser.ast.query.InsertQuery;
import com.example.database.processor.parser.ast.query.RollbackQuery;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.processor.parser.ast.query.ShowDatabasesQuery;
import com.example.database.processor.parser.ast.query.ShowTablesQuery;
import com.example.database.processor.parser.ast.query.UpdateQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic checks for DDL and DML/DQL. Reads catalog; never mutates it.
 * Column ids and index lists are copied onto analyzed DML so the planner
 * does not need CatalogManager.
 */
public final class DefaultQueryAnalyser implements QueryAnalyser {

    private final CatalogManager catalogManager;

    public DefaultQueryAnalyser(CatalogManager catalogManager) {
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager");
    }

    @Override
    public AnalyzedQuery analyse(AstNode ast) {
        Objects.requireNonNull(ast, "ast");
        if (ast instanceof CreateTableQuery createTable) {
            return analyseCreateTable(createTable);
        }
        if (ast instanceof DropTableQuery dropTable) {
            return analyseDropTable(dropTable);
        }
        if (ast instanceof CreateDatabaseQuery createDatabase) {
            return analyseCreateDatabase(createDatabase);
        }
        if (ast instanceof DropDatabaseQuery dropDatabase) {
            return analyseDropDatabase(dropDatabase);
        }
        if (ast instanceof AlterTableQuery alterTable) {
            return analyseAlterTable(alterTable);
        }
        if (ast instanceof CreateIndexQuery createIndex) {
            return analyseCreateIndex(createIndex);
        }
        if (ast instanceof DropIndexQuery dropIndex) {
            return analyseDropIndex(dropIndex);
        }
        if (ast instanceof BeginQuery) {
            return new AnalyzedBegin();
        }
        if (ast instanceof CommitQuery) {
            return new AnalyzedCommit();
        }
        if (ast instanceof RollbackQuery) {
            return new AnalyzedRollback();
        }
        if (ast instanceof CheckpointQuery) {
            return new AnalyzedCheckpoint();
        }
        if (ast instanceof DescribeTableQuery describe) {
            return analyseDescribeTable(describe);
        }
        if (ast instanceof ShowDatabasesQuery) {
            return new AnalyzedShowDatabases();
        }
        if (ast instanceof ShowTablesQuery showTables) {
            return analyseShowTables(showTables);
        }
        if (ast instanceof SelectQuery select) {
            return analyseSelect(select);
        }
        if (ast instanceof InsertQuery insert) {
            return analyseInsert(insert);
        }
        if (ast instanceof UpdateQuery update) {
            return analyseUpdate(update);
        }
        if (ast instanceof DeleteQuery delete) {
            return analyseDelete(delete);
        }
        return new UnresolvedQuery(ast);
    }

    private AnalyzedSelect analyseSelect(SelectQuery query) {
        TableMetadata table = requireExistingTable(query.table());
        List<ResolvedProjection> projections;
        if (query.star()) {
            projections = new ArrayList<>(table.columns().size());
            for (ColumnMetadata column : table.columns()) {
                projections.add(ResolvedProjection.column(column));
            }
        } else {
            projections = new ArrayList<>(query.projections().size());
            for (Expression projection : query.projections()) {
                projections.add(resolveProjection(projection, table));
            }
        }
        checkWhere(query.where().orElse(null), table);
        return new AnalyzedSelect(
                table.database(),
                table.name(),
                projections,
                query.where().orElse(null),
                table.columns(),
                table.indexes()
        );
    }

    private AnalyzedInsert analyseInsert(InsertQuery query) {
        TableMetadata table = requireExistingTable(query.table());
        List<Expression> rawValues = query.values();
        List<String> listedColumns = query.columns();
        if (listedColumns.isEmpty()) {
            if (rawValues.size() != table.columns().size()) {
                throw new AnalysisException(
                        "expected " + table.columns().size() + " values but got " + rawValues.size()
                );
            }
            List<ResolvedInsertValue> values = new ArrayList<>(rawValues.size());
            for (int i = 0; i < rawValues.size(); i++) {
                ColumnMetadata column = table.columns().get(i);
                values.add(resolveInsertValue(column, rawValues.get(i)));
            }
            return new AnalyzedInsert(table.database(), table.name(), values);
        }
        if (listedColumns.size() != rawValues.size()) {
            throw new AnalysisException(
                    "expected " + listedColumns.size() + " values but got " + rawValues.size()
            );
        }
        Set<String> seen = new HashSet<>();
        Map<Integer, ResolvedInsertValue> byColumnId = new LinkedHashMap<>();
        for (int i = 0; i < listedColumns.size(); i++) {
            String columnName = listedColumns.get(i);
            if (!seen.add(columnName)) {
                throw new AnalysisException("duplicate column name: " + columnName);
            }
            ColumnMetadata column = requireColumn(table, columnName);
            byColumnId.put(column.columnId().orElseThrow(), resolveInsertValue(column, rawValues.get(i)));
        }
        List<ResolvedInsertValue> values = new ArrayList<>(table.columns().size());
        for (ColumnMetadata column : table.columns()) {
            int columnId = column.columnId().orElseThrow();
            ResolvedInsertValue supplied = byColumnId.get(columnId);
            if (supplied != null) {
                values.add(supplied);
                continue;
            }
            // No NULL token yet; omitted nullable columns become null rather than failing INSERT.
            if (!column.nullable()) {
                throw new AnalysisException("column is not nullable: " + column.name());
            }
            values.add(new ResolvedInsertValue(columnId, column.type(), null));
        }
        return new AnalyzedInsert(table.database(), table.name(), values);
    }

    private AnalyzedUpdate analyseUpdate(UpdateQuery query) {
        TableMetadata table = requireExistingTable(query.table());
        List<ResolvedAssignment> assignments = new ArrayList<>(query.assignments().size());
        Set<String> seen = new HashSet<>();
        for (Assignment assignment : query.assignments()) {
            if (!seen.add(assignment.column())) {
                throw new AnalysisException("duplicate column name: " + assignment.column());
            }
            ColumnMetadata column = requireColumn(table, assignment.column());
            ColumnType valueType = typeOf(assignment.value(), table);
            if (valueType != column.type()) {
                throw new AnalysisException(
                        "type mismatch: expected " + column.type() + " but got " + valueType
                );
            }
            assignments.add(new ResolvedAssignment(
                    column.columnId().orElseThrow(),
                    column.type(),
                    assignment.value()
            ));
        }
        checkWhere(query.where().orElse(null), table);
        return new AnalyzedUpdate(
                table.database(),
                table.name(),
                assignments,
                query.where().orElse(null),
                table.columns(),
                table.indexes()
        );
    }

    private AnalyzedDelete analyseDelete(DeleteQuery query) {
        TableMetadata table = requireExistingTable(query.table());
        checkWhere(query.where().orElse(null), table);
        return new AnalyzedDelete(
                table.database(),
                table.name(),
                query.where().orElse(null),
                table.columns(),
                table.indexes()
        );
    }

    private AnalyzedDescribeTable analyseDescribeTable(DescribeTableQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        return new AnalyzedDescribeTable(database, table);
    }

    private AnalyzedShowTables analyseShowTables(ShowTablesQuery query) {
        String database = query.database();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        return new AnalyzedShowTables(database);
    }

    private AnalyzedCreateTable analyseCreateTable(CreateTableQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table already exists: " + target.qualifiedName());
        }
        List<ColumnDefinition> parsedColumns = query.columns();
        if (parsedColumns.isEmpty()) {
            throw new AnalysisException("table must have at least one column: " + target.qualifiedName());
        }
        Set<String> seenNames = new HashSet<>();
        List<ColumnMetadata> columns = new ArrayList<>(parsedColumns.size());
        for (ColumnDefinition column : parsedColumns) {
            if (!seenNames.add(column.name())) {
                throw new AnalysisException("duplicate column name: " + column.name());
            }
            columns.add(ColumnMetadata.define(column.name(), toColumnType(column.type())));
        }
        return new AnalyzedCreateTable(database, table, columns, query.primaryKeyColumn());
    }

    private AnalyzedDropTable analyseDropTable(DropTableQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        return new AnalyzedDropTable(database, table);
    }

    private AnalyzedCreateDatabase analyseCreateDatabase(CreateDatabaseQuery query) {
        String database = query.name();
        if (catalogManager.databaseExists(database)) {
            throw new AnalysisException("database already exists: " + database);
        }
        return new AnalyzedCreateDatabase(database);
    }

    private AnalyzedDropDatabase analyseDropDatabase(DropDatabaseQuery query) {
        String database = query.name();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        for (TableMetadata table : catalogManager.allTables()) {
            if (table.database().equals(database)) {
                throw new AnalysisException("database is not empty: " + database);
            }
        }
        return new AnalyzedDropDatabase(database);
    }

    private AnalyzedQuery analyseAlterTable(AlterTableQuery query) {
        return switch (query.action()) {
            case ADD_COLUMN -> analyseAddColumn(query);
            case DROP_COLUMN -> analyseDropColumn(query);
        };
    }

    private AnalyzedAddColumn analyseAddColumn(AlterTableQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        String columnName = query.column();
        TableMetadata existing = catalogManager.getTable(database, table).orElseThrow();
        for (ColumnMetadata existingColumn : existing.columns()) {
            if (existingColumn.name().equals(columnName)) {
                throw new AnalysisException("duplicate column name: " + columnName);
            }
        }
        ColumnMetadata column = ColumnMetadata.define(
                columnName,
                toColumnType(query.addColumnType().orElseThrow())
        );
        return new AnalyzedAddColumn(database, table, column);
    }

    private AnalyzedDropColumn analyseDropColumn(AlterTableQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        String columnName = query.column();
        TableMetadata existing = catalogManager.getTable(database, table).orElseThrow();
        ColumnMetadata targetColumn = null;
        for (ColumnMetadata column : existing.columns()) {
            if (column.name().equals(columnName)) {
                targetColumn = column;
                break;
            }
        }
        if (targetColumn == null) {
            throw new AnalysisException("column does not exist: " + columnName);
        }
        if (existing.columns().size() <= 1) {
            throw new AnalysisException("cannot drop last column: " + columnName);
        }
        int targetColumnId = targetColumn.columnId().orElseThrow();
        for (IndexMetadata index : existing.indexes()) {
            if (index.columnIds().contains(targetColumnId)) {
                throw new AnalysisException("index references column: " + index.name());
            }
        }
        return new AnalyzedDropColumn(database, table, columnName);
    }

    private AnalyzedCreateIndex analyseCreateIndex(CreateIndexQuery query) {
        QualifiedTable target = query.table();
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        TableMetadata existing = catalogManager.getTable(database, table).orElseThrow();
        for (IndexMetadata existingIndex : existing.indexes()) {
            if (existingIndex.name().equals(query.index())) {
                throw new AnalysisException("index already exists: " + query.index());
            }
        }
        List<String> columnNames = query.columns();
        if (columnNames.isEmpty()) {
            throw new AnalysisException("index must reference at least one column");
        }
        List<Integer> columnIds = new ArrayList<>(columnNames.size());
        for (String columnName : columnNames) {
            Integer columnId = findColumnId(existing, columnName);
            if (columnId == null) {
                throw new AnalysisException("column does not exist: " + columnName);
            }
            columnIds.add(columnId);
        }
        return new AnalyzedCreateIndex(database, table, query.index(), columnIds, query.unique());
    }

    private AnalyzedDropIndex analyseDropIndex(DropIndexQuery query) {
        String indexName = query.index();
        String foundDatabase = null;
        String foundTable = null;
        for (TableMetadata table : catalogManager.allTables()) {
            for (IndexMetadata index : table.indexes()) {
                if (!index.name().equals(indexName)) {
                    continue;
                }
                if (foundDatabase != null) {
                    throw new AnalysisException("ambiguous index name: " + indexName);
                }
                foundDatabase = table.database();
                foundTable = table.name();
            }
        }
        if (foundDatabase == null) {
            throw new AnalysisException("index does not exist: " + indexName);
        }
        return new AnalyzedDropIndex(foundDatabase, foundTable, indexName);
    }

    private TableMetadata requireExistingTable(QualifiedTable target) {
        String database = target.database();
        String table = target.table();
        if (!catalogManager.databaseExists(database)) {
            throw new AnalysisException("database does not exist: " + database);
        }
        if (!catalogManager.tableExists(database, table)) {
            throw new AnalysisException("table does not exist: " + target.qualifiedName());
        }
        return catalogManager.getTable(database, table).orElseThrow();
    }

    private static ColumnMetadata requireColumn(TableMetadata table, String columnName) {
        for (ColumnMetadata column : table.columns()) {
            if (column.name().equals(columnName)) {
                return column;
            }
        }
        throw new AnalysisException("column does not exist: " + columnName);
    }

    private static ResolvedProjection resolveProjection(Expression expression, TableMetadata table) {
        if (expression instanceof ColumnExpression column) {
            return ResolvedProjection.column(requireColumn(table, column.name()));
        }
        if (expression instanceof LiteralExpression literal) {
            Object coerced = coerceLiteral(literal.value(), literalType(literal.value()));
            return ResolvedProjection.literal(literalType(literal.value()), coerced);
        }
        throw new AnalysisException("unsupported select expression");
    }

    private static ResolvedInsertValue resolveInsertValue(ColumnMetadata column, Expression expression) {
        if (!(expression instanceof LiteralExpression literal)) {
            throw new AnalysisException("INSERT values must be literals");
        }
        Object coerced = coerceLiteral(literal.value(), column.type());
        return new ResolvedInsertValue(column.columnId().orElseThrow(), column.type(), coerced);
    }

    private static void checkWhere(Expression where, TableMetadata table) {
        if (where == null) {
            return;
        }
        // A non-boolean WHERE (bare INT column) would otherwise pass name resolution
        // and fail only at execute, after a scan had already started.
        ColumnType type = typeOf(where, table);
        if (type != ColumnType.BOOLEAN) {
            throw new AnalysisException("WHERE must be a boolean expression");
        }
    }

    private static ColumnType typeOf(Expression expression, TableMetadata table) {
        if (expression instanceof ColumnExpression column) {
            return requireColumn(table, column.name()).type();
        }
        if (expression instanceof LiteralExpression literal) {
            return literalType(literal.value());
        }
        if (expression instanceof BinaryExpression binary) {
            ColumnType left = typeOf(binary.left(), table);
            ColumnType right = typeOf(binary.right(), table);
            if (left != right) {
                throw new AnalysisException("type mismatch: " + left + " vs " + right);
            }
            if (left == ColumnType.BOOLEAN
                    && binary.operator() != TokenCatalog.EQ
                    && binary.operator() != TokenCatalog.NEQ) {
                throw new AnalysisException("boolean comparison must be = or !=");
            }
            return ColumnType.BOOLEAN;
        }
        throw new AnalysisException("unsupported expression");
    }

    /**
     * Parser numbers are Long/Double; catalog INT is a 32-bit slot. Rejecting here
     * avoids silent truncation when Volcano later writes the row.
     */
    private static ColumnType literalType(Object value) {
        if (value instanceof Long longValue) {
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new AnalysisException("integer literal out of range: " + longValue);
            }
            return ColumnType.INT;
        }
        if (value instanceof String) {
            return ColumnType.VARCHAR;
        }
        if (value instanceof Boolean) {
            return ColumnType.BOOLEAN;
        }
        if (value instanceof Double) {
            throw new AnalysisException("unsupported literal type: FLOAT");
        }
        throw new AnalysisException("unsupported literal type: " + value.getClass().getSimpleName());
    }

    private static Object coerceLiteral(Object value, ColumnType expected) {
        ColumnType inferred = literalType(value);
        if (inferred != expected) {
            throw new AnalysisException("type mismatch: expected " + expected + " but got " + inferred);
        }
        if (expected == ColumnType.INT && value instanceof Long longValue) {
            return longValue.intValue();
        }
        return value;
    }

    private static Integer findColumnId(TableMetadata table, String columnName) {
        for (ColumnMetadata column : table.columns()) {
            if (column.name().equals(columnName)) {
                return column.columnId().orElseThrow();
            }
        }
        return null;
    }

    private static ColumnType toColumnType(ColumnSqlType sqlType) {
        // Parser and catalog enums share names for Phase 1; executor may add width/nullable later.
        return ColumnType.valueOf(sqlType.name());
    }
}
