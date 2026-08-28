package com.example.database.processor.parser;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.ColumnSqlType;

/**
 * Shared column-type grammar for CREATE TABLE and ALTER TABLE ADD COLUMN.
 */
public final class ColumnTypeParser {

    private ColumnTypeParser() {
    }

    public static ColumnSqlType parse(TokenStream stream) {
        Token token = stream.peek();
        ColumnSqlType type = switch (token.kind()) {
            case INT -> ColumnSqlType.INT;
            case VARCHAR -> ColumnSqlType.VARCHAR;
            case BOOLEAN_TYPE -> ColumnSqlType.BOOLEAN;
            default -> null;
        };
        if (type == null) {
            throw new ParseException(
                    token.index(),
                    "expected column type INT, VARCHAR, or BOOLEAN but found " + token.kind()
            );
        }
        stream.consume();
        return type;
    }
}
