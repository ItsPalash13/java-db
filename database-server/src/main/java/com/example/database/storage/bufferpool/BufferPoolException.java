package com.example.database.storage.bufferpool;

/**
 * Thrown when the pool cannot satisfy {@code pin}/{@code newPage} (no clean victim),
 * or a caller misuses pin/latch/dirty (unpin at pinCount 0, markDirty unpinned, foreign frame).
 * <p>
 * Not a SQL error string — a later FileTableStore maps this to a client-facing message.
 */
public final class BufferPoolException extends RuntimeException {

    public BufferPoolException(String message) {
        super(message);
    }

    public BufferPoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
