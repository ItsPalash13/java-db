package com.example.database.engine.parser;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.statement.AlterParser;
import com.example.database.engine.parser.statement.CreateParser;
import com.example.database.engine.parser.statement.DeleteParser;
import com.example.database.engine.parser.statement.DropParser;
import com.example.database.engine.parser.statement.InsertParser;
import com.example.database.engine.parser.statement.SelectParser;
import com.example.database.engine.parser.statement.UpdateParser;

import java.util.List;

/**
 * Entry parser: wraps tokens in a {@link TokenStream}, dispatches via {@link ParserRegistry},
 * and requires a trailing EOF.
 */
public final class DefaultQueryParser implements QueryParser {

    private final ParserRegistry registry;

    public DefaultQueryParser() {
        this.registry = new ParserRegistry();
        registry.register(TokenCatalog.CREATE, new CreateParser());
        registry.register(TokenCatalog.ALTER, new AlterParser());
        registry.register(TokenCatalog.DROP, new DropParser());
        registry.register(TokenCatalog.SELECT, new SelectParser());
        registry.register(TokenCatalog.UPDATE, new UpdateParser());
        registry.register(TokenCatalog.INSERT, new InsertParser());
        registry.register(TokenCatalog.DELETE, new DeleteParser());
    }

    @Override
    public AstNode parse(List<Token> tokens) {
        TokenStream stream = new TokenStream(tokens);
        Token head = stream.peek();
        Parser statementParser = registry.getParser(head.kind());
        if (statementParser == null) {
            throw new ParseException(
                    head.index(),
                    "unsupported statement starting with " + head.kind()
            );
        }
        AstNode ast = statementParser.parse(stream);
        stream.expect(TokenCatalog.EOF);
        return ast;
    }
}
