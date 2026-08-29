package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

/**
 * Client-visible transaction start: {@code BEGIN} or {@code BEGIN TRANSACTION}.
 */
public record BeginQuery() implements Query {
}
