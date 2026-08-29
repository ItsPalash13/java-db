package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.RollbackQuery;

/**
 * ROLLBACK [TRANSACTION]
 */
public final class RollbackParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.ROLLBACK);
        stream.match(TokenCatalog.TRANSACTION);
        return new RollbackQuery();
    }
}
