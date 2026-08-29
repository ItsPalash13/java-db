package com.example.client.wire;

/** Client could not parse a response frame as {@link WireResponse}. */
public final class WireParseException extends RuntimeException {

    public WireParseException(String message) {
        super(message);
    }

    public WireParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
