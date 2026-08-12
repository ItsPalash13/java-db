package com.example.database.network;

import java.util.Objects;
import java.util.UUID;

public final class ConnectionId {

    private final String value;

    public ConnectionId(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static ConnectionId random() {
        return new ConnectionId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConnectionId that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
