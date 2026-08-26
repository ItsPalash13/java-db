package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.DeleteQuery;
import com.example.database.processor.parser.expression.ExpressionParser;

/**
 * DELETE FROM ident DOT ident [WHERE expr]
 */
public final class DeleteParser implements Parser {

    private final ExpressionParser expressions = new ExpressionParser();

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.DELETE);
        stream.expect(TokenCatalog.FROM);
        QualifiedTable table = QualifiedNames.parseTable(stream);

        Expression where = null;
        if (stream.match(TokenCatalog.WHERE)) {
            where = expressions.parse(stream);
        }

        return new DeleteQuery(table, where);
    }
}
