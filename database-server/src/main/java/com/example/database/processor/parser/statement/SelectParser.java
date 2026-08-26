package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.SelectQuery;
import com.example.database.processor.parser.expression.ExpressionParser;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT (* | expr (, expr)*) FROM ident DOT ident [WHERE expr]
 */
public final class SelectParser implements Parser {

    private final ExpressionParser expressions = new ExpressionParser();

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.SELECT);

        boolean star = false;
        List<Expression> projections = new ArrayList<>();
        if (stream.match(TokenCatalog.STAR)) {
            star = true;
        } else {
            projections.add(expressions.parsePrimary(stream));
            while (stream.match(TokenCatalog.COMMA)) {
                projections.add(expressions.parsePrimary(stream));
            }
        }

        stream.expect(TokenCatalog.FROM);
        QualifiedTable table = QualifiedNames.parseTable(stream);

        Expression where = null;
        if (stream.match(TokenCatalog.WHERE)) {
            where = expressions.parse(stream);
        }

        return new SelectQuery(star, projections, table, where);
    }
}
