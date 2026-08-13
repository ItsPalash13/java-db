package com.example.database.engine.parser.statement;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ParseException;
import com.example.database.engine.parser.Parser;
import com.example.database.engine.parser.TokenStream;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.ast.query.CreateDatabaseQuery;
import com.example.database.engine.parser.ast.query.CreateTableQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE DATABASE ident
 * CREATE TABLE ident (ident (, ident)*)
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
            List<String> columns = new ArrayList<>();
            columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
            while (stream.match(TokenCatalog.COMMA)) {
                columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
            }
            stream.expect(TokenCatalog.RPAREN);
            return new CreateTableQuery(table, columns);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected DATABASE or TABLE but found " + bad.kind()
        );
    }
}
