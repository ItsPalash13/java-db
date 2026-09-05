package com.example.database.storage.page;

/**
 * Discriminator in the page header. Heap pages and (later) B+ tree nodes share
 * the same 16 KiB container size so one BufferPool can cache both; the type
 * byte tells the codec which layout to expect inside.
 */
public enum PageType {
    HEAP((byte) 1),
    INDEX_META((byte) 2),
    INDEX_LEAF((byte) 3),
    INDEX_INTERNAL((byte) 4),
    /** Page 0 of {@code .ibd}: stamps {@code PAGE_SIZE}; heap rows start at page 1. */
    HEAP_META((byte) 5);

    private final byte code;

    PageType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static PageType fromCode(byte code) {
        for (PageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new PageLayoutException("unknown pageType code: " + (code & 0xFF));
    }
}
