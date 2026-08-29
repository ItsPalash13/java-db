package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

/**
 * Client-visible transaction commit: {@code COMMIT} or {@code COMMIT TRANSACTION}.
 */
public record CommitQuery() implements Query {
}
