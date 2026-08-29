package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

/**
 * Client-visible transaction abort: {@code ROLLBACK} or {@code ROLLBACK TRANSACTION}.
 */
public record RollbackQuery() implements Query {
}
