package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

/**
 * {@code SHOW TABLES FROM shop}
 */
public record ShowTablesQuery(String database) implements Query {
}
