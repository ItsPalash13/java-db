package com.example.database.engine;

import com.example.database.engine.lexer.DefaultQueryLexer;
import com.example.database.engine.lexer.LexException;
import com.example.database.engine.lexer.QueryLexer;
import com.example.database.engine.lexer.Token;
import com.example.database.engine.parser.DefaultQueryParser;
import com.example.database.engine.parser.ParseException;
import com.example.database.engine.parser.QueryParser;
import com.example.database.engine.parser.ast.AstNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default engine stub: lexes, parses, then echoes {@code OK <query>}.
 * Owns and coordinates {@link QueryLexer} and {@link QueryParser}.
 * Lex and parse errors are returned as a response with the exact index.
 */
public final class DefaultQueryEngine implements QueryEngine {

    private final QueryLexer lexer;
    private final QueryParser parser;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DefaultQueryEngine() {
        this.lexer = new DefaultQueryLexer();
        this.parser = new DefaultQueryParser();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[QueryEngine] started");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        System.out.println("[QueryEngine] stopped");
    }

    @Override
    public String execute(String query) {
        if (!running.get()) {
            throw new IllegalStateException("QueryEngine is not started");
        }
        System.out.println("[QueryEngine] executing query: " + query);
        final List<Token> tokens;
        try {
            tokens = lexer.tokenize(query);
        } catch (LexException e) {
            String error = e.toResponse();
            System.out.println("[QueryEngine] lex error: " + error);
            return error;
        }
        System.out.println("[QueryEngine] tokens: " + tokens);
        final AstNode ast;
        try {
            ast = parser.parse(tokens);
        } catch (ParseException e) {
            String error = e.toResponse();
            System.out.println("[QueryEngine] parse error: " + error);
            return error;
        }
        System.out.println("[QueryEngine] ast: " + ast);
        String result = "OK " + query;
        System.out.println("[QueryEngine] result: " + result);
        return result;
    }
}
