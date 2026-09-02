package com.example.database.storage.page;

/**
 * Physical address of a row on a heap page: which page, which slot directory entry.
 * <p>
 * This is not a lock key. {@code LockManager} / {@code UndoManager} / {@code Tuple}
 * still name rows by the logical {@code rowId} assigned at insert. A Rid only
 * answers "where are those bytes on disk?"
 *
 * @param pageId zero-based page number; disk offset = pageId × pageSize
 * @param slotId index into that page's slot directory (stable across in-place UPDATE)
 */
public record Rid(int pageId, int slotId) {

    public Rid {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be >= 0");
        }
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId must be >= 0");
        }
    }
}
