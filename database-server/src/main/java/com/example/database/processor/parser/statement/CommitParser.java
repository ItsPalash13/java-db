package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.CommitQuery;

/**
 * COMMIT [TRANSACTION]
 */
public final class CommitParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.COMMIT);
        stream.match(TokenCatalog.TRANSACTION);
        return new CommitQuery();
    }
}
