package com.example.database.engine.parser.statement;

import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.Parser;
import com.example.database.engine.parser.TokenStream;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.ast.Expression;
import com.example.database.engine.parser.ast.query.DeleteQuery;
import com.example.database.engine.parser.expression.ExpressionParser;

/**
 * DELETE FROM ident [WHERE expr]
 */
public final class DeleteParser implements Parser {

    private final ExpressionParser expressions = new ExpressionParser();

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.DELETE);
        stream.expect(TokenCatalog.FROM);
        String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();

        Expression where = null;
        if (stream.match(TokenCatalog.WHERE)) {
            where = expressions.parse(stream);
        }

        return new DeleteQuery(table, where);
    }
}
