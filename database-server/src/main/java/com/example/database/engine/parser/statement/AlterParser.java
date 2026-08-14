package com.example.database.engine.parser.statement;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ParseException;
import com.example.database.engine.parser.Parser;
import com.example.database.engine.parser.TokenStream;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.ast.query.AlterTableQuery;

/**
 * ALTER TABLE ident ADD [COLUMN] ident
 * ALTER TABLE ident DROP [COLUMN] ident
 */
public final class AlterParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.ALTER);
        stream.expect(TokenCatalog.TABLE);
        String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();

        if (stream.match(TokenCatalog.ADD)) {
            stream.match(TokenCatalog.COLUMN);
            String column = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new AlterTableQuery(table, AlterTableQuery.Action.ADD_COLUMN, column);
        }

        if (stream.match(TokenCatalog.DROP)) {
            stream.match(TokenCatalog.COLUMN);
            String column = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new AlterTableQuery(table, AlterTableQuery.Action.DROP_COLUMN, column);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected ADD or DROP but found " + bad.kind()
        );
    }
}
