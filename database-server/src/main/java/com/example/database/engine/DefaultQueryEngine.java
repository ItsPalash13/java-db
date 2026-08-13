package com.example.database.engine;

import com.example.database.engine.lexer.DefaultQueryLexer;
import com.example.database.engine.lexer.QueryLexer;
import com.example.database.engine.lexer.Token;
import com.example.database.engine.parser.AstNode;
import com.example.database.engine.parser.DefaultQueryParser;
import com.example.database.engine.parser.QueryParser;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default engine stub: lexes, parses, then echoes {@code OK <query>}.
 * Owns and coordinates {@link QueryLexer} and {@link QueryParser}.
 * Swap at the composition root ({@code Main}) with any other {@link QueryEngine}.
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
        List<Token> tokens = lexer.tokenize(query);
        System.out.println("[QueryEngine] tokens: " + tokens);
        AstNode ast = parser.parse(tokens);
        System.out.println("[QueryEngine] ast: " + ast);
        String result = "OK " + query;
        System.out.println("[QueryEngine] result: " + result);
        return result;
    }
}
