package com.example.database.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Unit tests for {@link ConnectionId} value semantics. */
class ConnectionIdTest {

    @Test
    void valueEquality() {
        ConnectionId left = new ConnectionId("conn-1");
        ConnectionId right = new ConnectionId("conn-1");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals("conn-1", left.value());
    }

    @Test
    void randomIdsDiffer() {
        assertNotEquals(ConnectionId.random(), ConnectionId.random());
    }
}
