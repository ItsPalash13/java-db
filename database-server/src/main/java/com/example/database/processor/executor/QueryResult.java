package com.example.database.processor.executor;

import com.example.database.network.wire.WireMessage;
import com.example.database.network.wire.WireProtocol;
import com.example.database.network.wire.WireResponse;

import java.util.List;
import java.util.Objects;

/**
 * Client-facing outcome of running a plan. DDL is a status string; introspection returns rows.
 */
public final class QueryResult {

    private final String errorMessage;
    private final String okMessage;
    private final List<WireMessage.ResultSet.Column> columns;
    private final List<List<Object>> rows;

    private QueryResult(
            String errorMessage,
            String okMessage,
            List<WireMessage.ResultSet.Column> columns,
            List<List<Object>> rows
    ) {
        this.errorMessage = errorMessage;
        this.okMessage = okMessage;
        this.columns = columns;
        this.rows = rows;
    }

    public static QueryResult ok() {
        return new QueryResult(null, null, null, null);
    }

    /** Unresolved statements still echo {@code OK <query>} in plain-text tests. */
    public static QueryResult okEcho(String message) {
        Objects.requireNonNull(message, "message");
        return new QueryResult(null, message, null, null);
    }

    public static QueryResult error(String message) {
        Objects.requireNonNull(message, "message");
        return new QueryResult(message, null, null, null);
    }

    public static QueryResult resultSet(
            List<WireMessage.ResultSet.Column> columns,
            List<List<Object>> rows
    ) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        return new QueryResult(null, null, List.copyOf(columns), List.copyOf(rows));
    }

    public boolean isError() {
        return errorMessage != null;
    }

    public boolean hasResultSet() {
        return columns != null;
    }

    public String toResponse() {
        if (errorMessage != null) {
            return errorMessage;
        }
        if (okMessage != null) {
            return okMessage;
        }
        return "OK";
    }

    public WireResponse toWireResponse() {
        if (errorMessage != null) {
            return new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Error(errorMessage)));
        }
        if (columns != null) {
            int rowCount = rows.size();
            return new WireResponse(
                    WireProtocol.VERSION,
                    List.of(
                            new WireMessage.ResultSet(columns, rows),
                            new WireMessage.Done(rowCount)
                    )
            );
        }
        return new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Ok(0)));
    }
}
