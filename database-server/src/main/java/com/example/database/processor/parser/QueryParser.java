package com.example.database.processor.parser;

import com.example.database.processor.lexer.Token;
import com.example.database.processor.parser.ast.AstNode;

import java.util.List;

/**
 * Turns a list of {@link Token}s into an {@link AstNode}.
 */
public interface QueryParser {

    AstNode parse(List<Token> tokens);
}
