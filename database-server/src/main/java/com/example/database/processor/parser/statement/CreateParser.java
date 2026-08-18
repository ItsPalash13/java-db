package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.CreateDatabaseQuery;
import com.example.database.processor.parser.ast.query.CreateIndexQuery;
import com.example.database.processor.parser.ast.query.CreateTableQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE DATABASE ident
 * CREATE TABLE ident (ident (, ident)*)
 * CREATE INDEX ident ON ident (ident (, ident)*)
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
            String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            stream.expect(TokenCatalog.LPAREN);
            List<String> columns = parseColumnList(stream);
            stream.expect(TokenCatalog.RPAREN);
            return new CreateTableQuery(table, columns);
        }

        if (stream.match(TokenCatalog.INDEX)) {
            String index = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            stream.expect(TokenCatalog.ON);
            String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            stream.expect(TokenCatalog.LPAREN);
            List<String> columns = parseColumnList(stream);
            stream.expect(TokenCatalog.RPAREN);
            return new CreateIndexQuery(index, table, columns);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected DATABASE, TABLE, or INDEX but found " + bad.kind()
        );
    }

    private static List<String> parseColumnList(TokenStream stream) {
        List<String> columns = new ArrayList<>();
        columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
        while (stream.match(TokenCatalog.COMMA)) {
            columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
        }
        return columns;
    }
}
