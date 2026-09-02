package com.example.database.storage.bufferpool;

import java.util.Objects;

/**
 * Identity of one page on disk: which file and which zero-based page number inside it.
 * Disk byte offset is {@code pageId * pageSize}. Page identity is {@code (file, pageId)} —
 * page 0 in {@code users.ibd} is not page 0 in {@code name.idx}.
 * <p>
 * Used as the BufferPool lookup key. Not a SQL lock key (that is {@code rowId} /
 * {@code LockKey}) and not a heap {@code Rid} (that adds {@code slotId} for a row).
 *
 * <pre>
 *   PageId heap = new PageId("shop/users/users.ibd", 3);
 *   // offset = 3 * pageSize within users.ibd
 * </pre>
 */
public record PageId(String file, int pageId) {

    /**
     * @param file   path relative to the data directory (e.g. {@code shop/users/users.ibd})
     * @param pageId zero-based page index within that file
     */
    public PageId {
        Objects.requireNonNull(file, "file");
        if (file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be >= 0");
        }
    }
}
