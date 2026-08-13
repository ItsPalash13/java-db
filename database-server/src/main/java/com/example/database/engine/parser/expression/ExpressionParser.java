package com.example.database.engine.parser.expression;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ParseException;
import com.example.database.engine.parser.TokenStream;
import com.example.database.engine.parser.ast.Expression;
import com.example.database.engine.parser.ast.expr.BinaryExpression;
import com.example.database.engine.parser.ast.expr.ColumnExpression;
import com.example.database.engine.parser.ast.expr.LiteralExpression;

/**
 * Parses simple expressions: column, literal, or left-associative comparisons.
 * Composed into statement parsers.
 */
public final class ExpressionParser {

    public Expression parse(TokenStream stream) {
        Expression left = parsePrimary(stream);
        while (isComparison(stream.peek().kind())) {
            TokenCatalog op = stream.consume().kind();
            Expression right = parsePrimary(stream);
            left = new BinaryExpression(left, op, right);
        }
        return left;
    }

    /** Projection item: * is handled by SelectParser; here column or literal only. */
    public Expression parsePrimary(TokenStream stream) {
        Token token = stream.peek();
        return switch (token.kind()) {
            case IDENTIFIER -> {
                stream.consume();
                yield new ColumnExpression(token.lexeme());
            }
            case STRING -> {
                stream.consume();
                yield new LiteralExpression(token.lexeme());
            }
            case NUMBER -> {
                stream.consume();
                yield parseNumberLiteral(token);
            }
            case BOOLEAN -> {
                stream.consume();
                yield new LiteralExpression(Boolean.valueOf(token.lexeme()));
            }
            default -> throw new ParseException(
                    token.index(),
                    "expected expression but found " + token.kind()
            );
        };
    }

    private static boolean isComparison(TokenCatalog kind) {
        return kind == TokenCatalog.EQ
                || kind == TokenCatalog.NEQ
                || kind == TokenCatalog.GT
                || kind == TokenCatalog.LT
                || kind == TokenCatalog.GTE
                || kind == TokenCatalog.LTE;
    }

    private static LiteralExpression parseNumberLiteral(Token token) {
        String lexeme = token.lexeme();
        if (lexeme.contains(".")) {
            return new LiteralExpression(Double.valueOf(lexeme));
        }
        return new LiteralExpression(Long.valueOf(lexeme));
    }
}
