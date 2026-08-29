package com.example.database.network.wire;

import java.util.List;

/**
 * One logical server message inside a {@link WireResponse}, mirroring PG/TDS ideas
 * (ERROR, row description + rows, DONE) without adopting their binary layouts.
 */
public sealed interface WireMessage permits WireMessage.Error, WireMessage.Ok, WireMessage.ResultSet, WireMessage.Done {

    /** Query failed; client should print and stop processing the batch. */
    record Error(String message) implements WireMessage {
    }

    /** DDL/DML succeeded with no row stream to return. */
    record Ok(int rowsAffected) implements WireMessage {
    }

    /** SELECT (future): column metadata plus row values as JSON arrays. */
    record ResultSet(List<Column> columns, List<List<Object>> rows) implements WireMessage {

        public record Column(String name, String type) {
        }
    }

    /** End of batch; optional row count for DML or SELECT footers. */
    record Done(int rowsAffected) implements WireMessage {
    }
}
