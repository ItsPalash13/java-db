package com.example.database.network;

/**
 * Outbound application message; {@link #encode()} produces bytes for the transport frame.
 */
public interface Response {

    byte[] encode();
}
