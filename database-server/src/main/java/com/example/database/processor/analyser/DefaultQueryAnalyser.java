package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.ColumnMetadata;
import com.example.database.storage.catalog.ColumnType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic checks for CREATE TABLE / CREATE DATABASE / DROP DATABASE. Reads catalog; never mutates it.
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
        if (ast instanceof CreateDatabaseQuery createDatabase) {
            return analyseCreateDatabase(createDatabase);
        }
        if (ast instanceof DropDatabaseQuery dropDatabase) {
            return analyseDropDatabase(dropDatabase);
        }
        return new UnresolvedQuery(ast);
    }

    private AnalyzedCreateTable analyseCreateTable(CreateTableQuery query) {
        String table = query.table();
        if (catalogManager.tableExists(table)) {
            throw new AnalysisException("table already exists: " + table);
        }
        List<ColumnDefinition> parsedColumns = query.columns();
        if (parsedColumns.isEmpty()) {
            throw new AnalysisException("table must have at least one column: " + table);
        }
        Set<String> seenNames = new HashSet<>();
        List<ColumnMetadata> columns = new ArrayList<>(parsedColumns.size());
        for (ColumnDefinition column : parsedColumns) {
            if (!seenNames.add(column.name())) {
                throw new AnalysisException("duplicate column name: " + column.name());
            }
            columns.add(ColumnMetadata.define(column.name(), toColumnType(column.type())));
        }
        return new AnalyzedCreateTable(table, columns);
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
        return new AnalyzedDropDatabase(database);
    }

    private static ColumnType toColumnType(ColumnSqlType sqlType) {
        // Parser and catalog enums share names for Phase 1; executor may add width/nullable later.
        return ColumnType.valueOf(sqlType.name());
    }
}
