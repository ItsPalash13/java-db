package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ColumnTypeParser;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.ColumnSqlType;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.AlterTableQuery;

/**
 * ALTER TABLE ident DOT ident ADD [COLUMN] ident type
 * ALTER TABLE ident DOT ident DROP [COLUMN] ident
 */
public final class AlterParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.ALTER);
        stream.expect(TokenCatalog.TABLE);
        QualifiedTable table = QualifiedNames.parseTable(stream);

        if (stream.match(TokenCatalog.ADD)) {
            stream.match(TokenCatalog.COLUMN);
            String column = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            ColumnSqlType type = ColumnTypeParser.parse(stream);
            return new AlterTableQuery(table, AlterTableQuery.Action.ADD_COLUMN, column, type);
        }

        if (stream.match(TokenCatalog.DROP)) {
            stream.match(TokenCatalog.COLUMN);
            String column = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
            return new AlterTableQuery(table, AlterTableQuery.Action.DROP_COLUMN, column, null);
        }

        Token bad = stream.peek();
        throw new ParseException(
                bad.index(),
                "expected ADD or DROP but found " + bad.kind()
        );
    }
}
