package com.example.client.wire;

import java.util.List;

/** Mirror of server {@code WireMessage}; decoded from JSON response frames. */
public sealed interface WireMessage permits WireMessage.Error, WireMessage.Ok, WireMessage.ResultSet, WireMessage.Done {

    record Error(String message) implements WireMessage {
    }

    record Ok(int rowsAffected) implements WireMessage {
    }

    record ResultSet(List<Column> columns, List<List<Object>> rows) implements WireMessage {

        public record Column(String name, String type) {
        }
    }

    record Done(int rowsAffected) implements WireMessage {
    }
}
