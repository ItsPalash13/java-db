package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.BeginQuery;

/**
 * BEGIN [TRANSACTION]
 */
public final class BeginParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.BEGIN);
        if (stream.match(TokenCatalog.TRANSACTION)) {
            // optional keyword
        }
        return new BeginQuery();
    }
}
