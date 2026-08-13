package com.example.database.network;

/**
 * Inbound application message after transport framing has been stripped.
 */
public interface Request {

    /** Decode the wire payload into an application string (currently UTF-8 text). */
    String decode();
}
