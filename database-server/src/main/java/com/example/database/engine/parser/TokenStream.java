package com.example.database.engine.parser;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.lexer.TokenCatalog;

import java.util.List;
import java.util.Objects;

/**
 * Cursor over a token list for statement parsers.
 */
public final class TokenStream {

    private final List<Token> tokens;
    private int position;

    public TokenStream(List<Token> tokens) {
        this.tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        if (this.tokens.isEmpty()) {
            throw new ParseException(0, "empty token list");
        }
        this.position = 0;
    }

    public Token peek() {
        return tokens.get(position);
    }

    public Token consume() {
        Token token = peek();
        if (token.kind() != TokenCatalog.EOF) {
            position++;
        }
        return token;
    }

    public boolean match(TokenCatalog kind) {
        if (peek().kind() == kind) {
            consume();
            return true;
        }
        return false;
    }

    public Token expect(TokenCatalog kind) {
        Token token = peek();
        if (token.kind() != kind) {
            throw new ParseException(
                    token.index(),
                    "expected " + kind + " but found " + token.kind()
            );
        }
        return consume();
    }

    public boolean check(TokenCatalog kind) {
        return peek().kind() == kind;
    }
}
