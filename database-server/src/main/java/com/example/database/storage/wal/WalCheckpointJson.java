package com.example.database.storage.wal;

import java.nio.charset.StandardCharsets;

/**
 * Tiny JSON for {@code wal.checkpoint}. Kept out of {@link WalJson} because that codec
 * encodes one redo line per object; the checkpoint file is a single barrier document,
 * not an append-only stream.
 */
final class WalCheckpointJson {

    private WalCheckpointJson() {
    }

    static byte[] toBytes(WalCheckpointMeta meta) {
        String json = "{\"maxTxnId\":" + meta.maxTxnId() + "}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static WalCheckpointMeta fromBytes(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            // Caller usually guards this; return 0 so a blank file never takes down startup.
            return new WalCheckpointMeta(0);
        }
        // Minimal parse on purpose: avoid pulling a JSON library into the WAL package
        // for one integer field. Corrupt files fail loud — better than silent wrong ids.
        String key = "\"maxTxnId\"";
        int keyAt = text.indexOf(key);
        if (keyAt < 0) {
            throw new WalException("wal.checkpoint missing maxTxnId");
        }
        int colon = text.indexOf(':', keyAt + key.length());
        if (colon < 0) {
            throw new WalException("wal.checkpoint malformed maxTxnId");
        }
        int start = colon + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            throw new WalException("wal.checkpoint maxTxnId is not a number");
        }
        try {
            return new WalCheckpointMeta(Integer.parseInt(text.substring(start, end)));
        } catch (NumberFormatException e) {
            throw new WalException("wal.checkpoint maxTxnId is not a number", e);
        }
    }
}
