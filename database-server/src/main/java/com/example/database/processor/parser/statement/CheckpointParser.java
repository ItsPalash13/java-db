package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.CheckpointQuery;

/**
 * CHECKPOINT — no operands. Admin forces the same durable-only WAL barrier the
 * background scheduler would run; parse stays trivial on purpose.
 */
public final class CheckpointParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.CHECKPOINT);
        return new CheckpointQuery();
    }
}
