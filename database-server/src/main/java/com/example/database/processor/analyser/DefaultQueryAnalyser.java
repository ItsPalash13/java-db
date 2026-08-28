package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.AlterTableQuery;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.DropIndexQuery;
import com.example.database.processor.parser.ast.query.DropTableQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic checks for CREATE/DROP TABLE, CREATE/DROP DATABASE, ALTER TABLE ADD/DROP COLUMN,
 * and CREATE/DROP INDEX. Reads catalog; never mutates it.
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
        return new UnresolvedQuery(ast);
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
        return new AnalyzedCreateTable(database, table, columns);
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
        return new AnalyzedCreateIndex(database, table, query.index(), columnIds);
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
