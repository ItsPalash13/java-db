package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.query.InsertQuery;
import com.example.database.processor.parser.expression.ExpressionParser;

import java.util.ArrayList;
import java.util.List;

/**
 * INSERT INTO ident [(ident (, ident)*)] VALUES (expr (, expr)*)
 */
public final class InsertParser implements Parser {

    private final ExpressionParser expressions = new ExpressionParser();

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.INSERT);
        stream.expect(TokenCatalog.INTO);
        String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();

        List<String> columns = new ArrayList<>();
        if (stream.match(TokenCatalog.LPAREN)) {
            columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
            while (stream.match(TokenCatalog.COMMA)) {
                columns.add(stream.expect(TokenCatalog.IDENTIFIER).lexeme());
            }
            stream.expect(TokenCatalog.RPAREN);
        }

        stream.expect(TokenCatalog.VALUES);
        stream.expect(TokenCatalog.LPAREN);
        List<Expression> values = new ArrayList<>();
        values.add(expressions.parse(stream));
        while (stream.match(TokenCatalog.COMMA)) {
            values.add(expressions.parse(stream));
        }
        stream.expect(TokenCatalog.RPAREN);

        return new InsertQuery(table, columns, values);
    }
}
