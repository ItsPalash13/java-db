package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.ShowDatabasesQuery;
import com.example.database.processor.parser.ast.query.ShowTablesQuery;

/**
 * SHOW DATABASES | SHOW TABLES FROM ident
 */
public final class ShowParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.SHOW);
        if (stream.match(TokenCatalog.DATABASES)) {
            return new ShowDatabasesQuery();
        }
        if (stream.match(TokenCatalog.TABLES)) {
            stream.expect(TokenCatalog.FROM);
            String database = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new ShowTablesQuery(database);
        }
        throw new ParseException(stream.peek().index(), "expected DATABASES or TABLES after SHOW");
    }
}
