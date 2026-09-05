package com.example.database.storage.page;

/**
 * On-disk constants for a fixed-size slotted page.
 * <p>
 * Layout (low → high): 24-byte header, slot directory growing down, free space,
 * then row payloads packed from {@code pageSize} upward. This is not a buffer
 * frame — pin/latch/dirty live in the BufferPool (Phase 3). Catalog JSON and
 * {@code wal.log} never use this format.
 */
public final class PageLayout {

    /** Default production page size; tests may construct smaller pages. */
    public static final int DEFAULT_PAGE_SIZE = 16 * 1024;

    /** Rejects a non-page file (e.g. catalog JSON) misinterpreted as a heap page. */
    public static final int MAGIC = 0x4A44; // 'J''D'

    public static final int HEADER_SIZE = 24;
    public static final int SLOT_SIZE = 4;
    /**
     * Smallest page that fits the shared header plus the index meta pageSize stamp.
     * One engine {@code PAGE_SIZE} must satisfy heap and index layouts.
     */
    public static final int MIN_PAGE_SIZE = HEADER_SIZE + Integer.BYTES;

    public static final int OFF_MAGIC = 0;
    public static final int OFF_PAGE_TYPE = 2;
    public static final int OFF_FLAGS = 3;
    public static final int OFF_PAGE_ID = 4;
    public static final int OFF_SLOT_COUNT = 8;
    public static final int OFF_LOWER = 10;
    public static final int OFF_UPPER = 12;
    public static final int OFF_PAD = 14;
    /** Reserved so DML WAL (Phase 6) can stamp a page LSN without shifting fields. */
    public static final int OFF_LSN_RESERVED = 16;

    private PageLayout() {
    }
}
