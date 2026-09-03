package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ColumnTypeParser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.ColumnDefinition;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE DATABASE ident
 * CREATE TABLE ident DOT ident (ident type (, ident type)*)
 * CREATE INDEX ident ON ident DOT ident (ident (, ident)*)
 */
public final class CreateParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.CREATE);

        if (stream.match(TokenCatalog.DATABASE)) {
            String name = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new CreateDatabaseQuery(name);
        }

        if (stream.match(TokenCatalog.TABLE)) {
            QualifiedTable table = QualifiedNames.parseTable(stream);
            stream.expect(TokenCatalog.LPAREN);
            List<ColumnDefinition> columns = parseColumnDefinitions(stream);
            stream.expect(TokenCatalog.RPAREN);
            return new CreateTableQuery(table, columns);
        }

        boolean unique = stream.match(TokenCatalog.UNIQUE);
        if (stream.match(TokenCatalog.INDEX)) {
            String index = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            stream.expect(TokenCatalog.ON);
            QualifiedTable table = QualifiedNames.parseTable(stream);
            stream.expect(TokenCatalog.LPAREN);
            List<String> columns = parseIndexColumnList(stream);
            stream.expect(TokenCatalog.RPAREN);
            return new CreateIndexQuery(index, table, columns, unique);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected DATABASE, TABLE, or INDEX but found " + bad.kind()
        );
    }

    private static List<ColumnDefinition> parseColumnDefinitions(TokenStream stream) {
        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(parseColumnDefinition(stream));
        while (stream.match(TokenCatalog.COMMA)) {
            columns.add(parseColumnDefinition(stream));
        }
        return columns;
    }

    private static ColumnDefinition parseColumnDefinition(TokenStream stream) {
        String name = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
        ColumnSqlType type = ColumnTypeParser.parse(stream);
        // PRIMARY KEY constraint after column type: CREATE TABLE t (id INT PRIMARY KEY, ...)
        boolean pk = false;
        if (stream.match(TokenCatalog.PRIMARY)) {
            stream.expect(TokenCatalog.KEY);
            pk = true;
        }
        return new ColumnDefinition(name, type, pk);
    }

    private static List<String> parseIndexColumnList(TokenStream stream) {
        List<String> columns = new ArrayList<>();
        columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
        while (stream.match(TokenCatalog.COMMA)) {
            columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
        }
        return columns;
    }
}
