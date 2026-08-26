package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.Assignment;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.Expression;
import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.query.UpdateQuery;
import com.example.database.processor.parser.expression.ExpressionParser;

import java.util.ArrayList;
import java.util.List;

/**
 * UPDATE ident DOT ident SET ident = expr (, ident = expr)* [WHERE expr]
 */
public final class UpdateParser implements Parser {

    private final ExpressionParser expressions = new ExpressionParser();

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.UPDATE);
        QualifiedTable table = QualifiedNames.parseTable(stream);
        stream.expect(TokenCatalog.SET);

        List<Assignment> assignments = new ArrayList<>();
        assignments.add(parseAssignment(stream));
        while (stream.match(TokenCatalog.COMMA)) {
            assignments.add(parseAssignment(stream));
        }

        Expression where = null;
        if (stream.match(TokenCatalog.WHERE)) {
            where = expressions.parse(stream);
        }

        return new UpdateQuery(table, assignments, where);
    }

    private Assignment parseAssignment(TokenStream stream) {
        String column = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
        stream.expect(TokenCatalog.EQ);
        Expression value = expressions.parse(stream);
        return new Assignment(column, value);
    }
}
