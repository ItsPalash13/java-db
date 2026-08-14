package com.example.database.engine.parser.statement;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ParseException;
import com.example.database.engine.parser.Parser;
import com.example.database.engine.parser.TokenStream;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.ast.query.DropDatabaseQuery;
import com.example.database.engine.parser.ast.query.DropIndexQuery;
import com.example.database.engine.parser.ast.query.DropTableQuery;

/**
 * DROP DATABASE ident
 * DROP TABLE ident
 * DROP INDEX ident
 */
public final class DropParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.DROP);

        if (stream.match(TokenCatalog.DATABASE)) {
            String name = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new DropDatabaseQuery(name);
        }

        if (stream.match(TokenCatalog.TABLE)) {
            String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new DropTableQuery(table);
        }

        if (stream.match(TokenCatalog.INDEX)) {
            String index = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new DropIndexQuery(index);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected DATABASE, TABLE, or INDEX but found " + bad.kind()
        );
    }
}
