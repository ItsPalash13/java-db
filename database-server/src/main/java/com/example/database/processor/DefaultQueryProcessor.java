package com.example.database.processor;

import com.example.database.processor.analyser.AnalysisException;
import com.example.database.processor.analyser.AnalyzedQuery;
import com.example.database.processor.analyser.DefaultQueryAnalyser;
import com.example.database.processor.analyser.QueryAnalyser;
import com.example.database.processor.lexer.DefaultQueryLexer;
import com.example.database.processor.lexer.LexException;
import com.example.database.processor.lexer.QueryLexer;
import com.example.database.processor.lexer.Token;
import com.example.database.processor.parser.DefaultQueryParser;
import com.example.database.processor.parser.ParseException;
import com.example.database.processor.parser.QueryParser;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Default query processor stub: lexes, parses, analyses, then echoes {@code OK <query>}.
 * Owns {@link QueryLexer} and {@link QueryParser}; builds {@link QueryAnalyser} per query
 * from {@link StorageEngine#catalogManager()} (storage must be started before {@code execute}).
 * Lex and parse errors are returned as a response with the exact index.
 */
public final class DefaultQueryProcessor implements QueryProcessor {

    private final QueryLexer lexer;
    private final QueryParser parser;
    private final StorageEngine storageEngine;

    public DefaultQueryProcessor() {
        this(new DefaultStorageEngine(DataDirectory.defaults()));
    }

    public DefaultQueryProcessor(Path dataDir) {
        this(new DefaultStorageEngine(new DataDirectory(dataDir)));
    }

    public DefaultQueryProcessor(StorageEngine storageEngine) {
        this.lexer = new DefaultQueryLexer();
        this.parser = new DefaultQueryParser();
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
        String result = "OK " + query;
        System.out.println("[QueryProcessor] result: " + result);
        return result;
    }
}
