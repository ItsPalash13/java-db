package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.Query;

/**
 * Force a durable WAL checkpoint: {@code CHECKPOINT}.
 * Not a transaction-control statement — recovery lifecycle, same action as the
 * background scheduler, invoked immediately on this connection.
 */
public record CheckpointQuery() implements Query {
}
