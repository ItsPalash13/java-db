package com.example.database.processor;

import com.example.database.processor.analyser.AnalysisException;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.DefaultQueryAnalyser;
import com.example.database.processor.analyser.QueryAnalyser;
import com.example.database.processor.executor.CommandExecutor;
import com.example.database.processor.executor.DescribeExecutor;
import com.example.database.processor.executor.ExecutionException;
import com.example.database.processor.executor.ExecutorRegistry;
import com.example.database.processor.executor.QueryDispatcher;
import com.example.database.processor.executor.QueryResult;
import com.example.database.processor.executor.TransactionControlExecutor;
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
import com.example.database.storage.lock.CatalogLockException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Lex → parse → analyse → plan → execute. DDL writes the catalog; DESCRIBE/SHOW read it.
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
    public QueryResult execute(String query) {
        System.out.println("[QueryProcessor] executing query: " + query);
        final List<Token> tokens;
        try {
            tokens = lexer.tokenize(query);
        } catch (LexException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] lex error: " + error);
            return QueryResult.error(error);
        }
        System.out.println("[QueryProcessor] tokens: " + tokens);
        final AstNode ast;
        try {
            ast = parser.parse(tokens);
        } catch (ParseException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] parse error: " + error);
            return QueryResult.error(error);
        }
        System.out.println("[QueryProcessor] ast: " + ast);
        final AnalyzedQuery analyzed;
        try {
            QueryAnalyser analyser = new DefaultQueryAnalyser(storageEngine.catalogManager());
            analyzed = analyser.analyse(ast);
        } catch (AnalysisException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] analyse error: " + error);
            return QueryResult.error(error);
        } catch (IllegalStateException e) {
            String error = "ERROR: " + e.getMessage();
            System.out.println("[QueryProcessor] analyse error: " + error);
            return QueryResult.error(error);
        }
        System.out.println("[QueryProcessor] analysed: " + analyzed);
        ExecutionPlan plan = planner.plan(analyzed);
        System.out.println("[QueryProcessor] plan: " + plan);
        if (plan instanceof UnresolvedPlan) {
            String result = "OK " + query;
            System.out.println("[QueryProcessor] result: " + result);
            return QueryResult.okEcho(result);
        }
        try {
            QueryResult result = queryDispatcher().execute(plan);
            System.out.println("[QueryProcessor] result: " + result.toResponse());
            return result;
        } catch (ExecutionException e) {
            String error = e.toResponse();
            System.out.println("[QueryProcessor] execute error: " + error);
            return QueryResult.error(error);
        } catch (CatalogLockException e) {
            String error = "ERROR: " + e.getMessage();
            System.out.println("[QueryProcessor] lock error: " + error);
            return QueryResult.error(error);
        }
    }

    @Override
    public void endConnectionSession() {
        storageEngine.transactionManager().endConnectionSession(
                storageEngine.lockManager(),
                storageEngine.catalogManager()
        );
    }

    private QueryDispatcher queryDispatcher() {
        if (queryDispatcher == null) {
            ExecutorRegistry registry = new ExecutorRegistry();
            CommandExecutor commands = new CommandExecutor(
                    storageEngine.catalogManager(),
                    storageEngine.transactionManager(),
                    storageEngine.lockManager(),
                    storageEngine.walManager()
            );
            TransactionControlExecutor transactionControl = new TransactionControlExecutor(
                    storageEngine.transactionManager(),
                    storageEngine.lockManager(),
                    storageEngine.catalogManager()
            );
            DescribeExecutor describe = new DescribeExecutor(storageEngine.catalogManager());
            registry.register(QueryType.CREATE_TABLE, commands);
            registry.register(QueryType.DROP_TABLE, commands);
            registry.register(QueryType.CREATE_DATABASE, commands);
            registry.register(QueryType.DROP_DATABASE, commands);
            registry.register(QueryType.ADD_COLUMN, commands);
            registry.register(QueryType.DROP_COLUMN, commands);
            registry.register(QueryType.CREATE_INDEX, commands);
            registry.register(QueryType.DROP_INDEX, commands);
            registry.register(QueryType.BEGIN, transactionControl);
            registry.register(QueryType.COMMIT, transactionControl);
            registry.register(QueryType.ROLLBACK, transactionControl);
            registry.register(QueryType.DESCRIBE_TABLE, describe);
            registry.register(QueryType.SHOW_DATABASES, describe);
            registry.register(QueryType.SHOW_TABLES, describe);
            queryDispatcher = new QueryDispatcher(registry);
        }
        return queryDispatcher;
    }
}
