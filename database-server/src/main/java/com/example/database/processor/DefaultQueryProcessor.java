package com.example.database.processor;

import com.example.database.processor.analyser.AnalysisException;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.DefaultQueryAnalyser;
import com.example.database.processor.analyser.QueryAnalyser;
import com.example.database.processor.executor.CommandExecutor;
import com.example.database.processor.executor.ExecutionException;
import com.example.database.processor.executor.ExecutorRegistry;
import com.example.database.processor.executor.QueryDispatcher;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.lexer.DefaultQueryLexer;
import com.example.database.processor.lexer.LexException;
import com.example.database.processor.lexer.QueryLexer;
import com.example.database.processor.lexer.Token;
import com.example.database.processor.parser.DefaultQueryParser;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.QueryParser;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.planner.DefaultQueryPlanner;
import com.example.database.processor.planner.ExecutionPlan;
import com.example.database.processor.planner.QueryPlanner;
import com.example.database.processor.planner.QueryType;
import com.example.database.processor.planner.UnresolvedPlan;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Lex → parse → analyse → plan → execute. CREATE/DROP TABLE and CREATE/DROP DATABASE
 * write the catalog; other statements still echo {@code OK <query>} until their own executor branches.
 */
public final class DefaultQueryProcessor implements QueryProcessor {

    private final QueryLexer lexer;
    private final QueryParser parser;
    private final QueryPlanner planner;
    private final StorageEngine storageEngine;
    private QueryDispatcher queryDispatcher;

    public DefaultQueryProcessor() {
        this(new DefaultStorageEngine(DataDirectory.defaults()));
    }

    public DefaultQueryProcessor(Path dataDir) {
        this(new DefaultStorageEngine(new DataDirectory(dataDir)));
    }

    public DefaultQueryProcessor(StorageEngine storageEngine) {
        this.lexer = new DefaultQueryLexer();
        this.parser = new DefaultQueryParser();
        this.planner = new DefaultQueryPlanner();
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    public StorageEngine storageEngine() {
        return storageEngine;
    }

    @Override
    public String execute(String query) {
        System.out.println("[QueryProcessor] executing query: " + query);
        final List<Token> tokens;
        try {
            tokens = lexer.tokenize(query);
        } catch (LexException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] lex error: " + error);
            return error;
        }
        System.out.println("[QueryProcessor] tokens: " + tokens);
        final AstNode ast;
        try {
            ast = parser.parse(tokens);
        } catch (ParseException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] parse error: " + error);
            return error;
        }
        System.out.println("[QueryProcessor] ast: " + ast);
        final AnalyzedQuery analyzed;
        try {
            QueryAnalyser analyser = new DefaultQueryAnalyser(storageEngine.catalogManager());
            analyzed = analyser.analyse(ast);
        } catch (AnalysisException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] analyse error: " + error);
            return error;
        } catch (IllegalStateException e) {
            // catalogManager() when StorageEngine.start() has not run yet.
            String error = "ERROR: " + e.getMessage();
            System.out.println("[QueryProcessor] analyse error: " + error);
            return error;
        }
        System.out.println("[QueryProcessor] analysed: " + analyzed);
        ExecutionPlan plan = planner.plan(analyzed);
        System.out.println("[QueryProcessor] plan: " + plan);
        if (plan instanceof UnresolvedPlan) {
            String result = "OK " + query;
            System.out.println("[QueryProcessor] result: " + result);
            return result;
        }
        try {
            QueryResult result = queryDispatcher().execute(plan);
            String response = result.toResponse();
            System.out.println("[QueryProcessor] result: " + response);
            return response;
        } catch (ExecutionException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] execute error: " + error);
            return error;
        }
    }

    private QueryDispatcher queryDispatcher() {
        // Built after start(): CatalogManager is illegal until StorageEngine.start(),
        // and Main constructs this processor before DatabaseServer.start().
        if (queryDispatcher == null) {
            ExecutorRegistry registry = new ExecutorRegistry();
            CommandExecutor commands = new CommandExecutor(storageEngine.catalogManager());
            registry.register(QueryType.CREATE_TABLE, commands);
            registry.register(QueryType.DROP_TABLE, commands);
            registry.register(QueryType.CREATE_DATABASE, commands);
            registry.register(QueryType.DROP_DATABASE, commands);
            queryDispatcher = new QueryDispatcher(registry);
        }
        return queryDispatcher;
    }
}
