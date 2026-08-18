package com.example.database.processor.parser;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.parser.ast.AstNode;

/**
 * Parses one statement kind from a {@link TokenStream} into an {@link AstNode}.
 */
public interface Parser {

    AstNode parse(TokenStream stream);
}
