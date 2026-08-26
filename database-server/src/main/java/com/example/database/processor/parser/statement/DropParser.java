package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.DropDatabaseQuery;
import com.example.database.processor.parser.ast.query.DropIndexQuery;
import com.example.database.processor.parser.ast.query.DropTableQuery;

/**
 * DROP DATABASE ident
 * DROP TABLE ident DOT ident
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
            QualifiedTable table = QualifiedNames.parseTable(stream);
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
