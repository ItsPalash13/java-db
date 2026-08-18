package com.example.database.processor.parser;

import com.example.database.processor.lexer.TokenCatalog;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps leading statement {@link TokenCatalog} kinds to statement {@link Parser}s.
 */
public final class ParserRegistry {

    private final Map<TokenCatalog, Parser> parsers = new HashMap<>();

    public void register(TokenCatalog kind, Parser parser) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parser, "parser");
        parsers.put(kind, parser);
    }

    public Parser getParser(TokenCatalog kind) {
        return parsers.get(kind);
    }
}
