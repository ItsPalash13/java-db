package com.example.database.storage.index;

import com.example.database.storage.page.PageLayout;

/**
 * Index-specific payload offsets inside the shared 24-byte page header region.
 * Meta page 0 stores the B+ tree root; leaf/internal pages store sibling/left-child links.
 */
public final class IndexPageLayout {

    /** Meta page 0: root page id (-1 = empty tree). */
    public static final int OFF_META_ROOT = PageLayout.OFF_LSN_RESERVED;
    /** Meta page 0: tree height (0 = empty, 1 = single leaf root). */
    public static final int OFF_META_HEIGHT = PageLayout.OFF_LSN_RESERVED + Integer.BYTES;
    /**
     * Meta page 0: durable {@code PAGE_SIZE} for this {@code .idx} (must match server env).
     * Lives just past the 24-byte shared header (root/height use the LSN-reserved region).
     */
    public static final int OFF_META_PAGE_SIZE = PageLayout.HEADER_SIZE;
    /**
     * Meta page 0: durable {@code INDEX_KEY_PADDING_BYTES} (trailing zeros after logical key).
     * Must match server env so decode/compare strip the same pad.
     */
    public static final int OFF_META_KEY_PADDING = OFF_META_PAGE_SIZE + Integer.BYTES;
    /** Bytes after the shared header occupied by pageSize + keyPadding stamps. */
    public static final int META_PAGE_SIZE_BYTES = Integer.BYTES + Integer.BYTES;

    /** Leaf page: next sibling leaf page id (-1 = none). */
    public static final int OFF_LEAF_NEXT = PageLayout.OFF_LSN_RESERVED;
    /** Internal page: leftmost child page id before first separator key. */
    public static final int OFF_INTERNAL_LEFT = PageLayout.OFF_LSN_RESERVED;

    /** Rid bytes appended after encoded key in a leaf entry. */
    public static final int RID_BYTES = Integer.BYTES + Integer.BYTES;

    /** Child page id bytes in an internal entry (after key). */
    public static final int CHILD_BYTES = Integer.BYTES;

    private IndexPageLayout() {
    }
}
