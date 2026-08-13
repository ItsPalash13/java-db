package com.example.database.engine.parser;

import com.example.database.engine.lexer.Token;
import com.example.database.engine.parser.ast.AstNode;

/**
 * Parses one statement kind from a {@link TokenStream} into an {@link AstNode}.
 */
public interface Parser {

    AstNode parse(TokenStream stream);
}
