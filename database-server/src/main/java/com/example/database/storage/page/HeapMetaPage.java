package com.example.database.storage.page;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Page 0 of every {@code .ibd} file: durable {@code PAGE_SIZE} stamp for the heap.
 * Not a row page — {@link HeapPage} data starts at page 1. Same fixed page size as
 * every other page in the file (one {@code PAGE_SIZE} for the whole engine).
 */
public final class HeapMetaPage {

    /** Stamped page size lives in the shared header’s LSN-reserved region. */
    public static final int OFF_PAGE_SIZE = PageLayout.OFF_LSN_RESERVED;

    private final byte[] data;

    private HeapMetaPage(byte[] data) {
        this.data = data;
    }

    /**
     * Empty HEAP_META image with {@code pageSize} written into the stamp field.
     */
    public static HeapMetaPage createEmpty(int pageId, int pageSize) {
        if (pageId != 0) {
            throw new IllegalArgumentException("heap meta must be page 0, got " + pageId);
        }
        if (pageSize < PageLayout.HEADER_SIZE + PageLayout.SLOT_SIZE) {
            throw new IllegalArgumentException("pageSize too small for heap meta: " + pageSize);
        }
        if (pageSize > 0xFFFF) {
            throw new IllegalArgumentException("pageSize must fit in u16: " + pageSize);
        }
        byte[] bytes = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.HEAP_META.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        buf.putShort(PageLayout.OFF_LOWER, (short) PageLayout.HEADER_SIZE);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putInt(OFF_PAGE_SIZE, pageSize);
        return new HeapMetaPage(bytes);
    }

    public static HeapMetaPage wrap(byte[] data) {
        Objects.requireNonNull(data, "data");
        HeapMetaPage page = new HeapMetaPage(data);
        page.validateHeader();
        return page;
    }

    public byte[] data() {
        return data;
    }

    public byte[] toBytes() {
        return data.clone();
    }

    /** Page size this {@code .ibd} was written with. */
    public int pageSize() {
        return buf().getInt(OFF_PAGE_SIZE);
    }

    public void setPageSize(int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + PageLayout.SLOT_SIZE || pageSize > 0xFFFF) {
            throw new PageLayoutException("invalid stamped pageSize: " + pageSize);
        }
        buf().putInt(OFF_PAGE_SIZE, pageSize);
    }

    private void validateHeader() {
        ByteBuffer header = buf();
        int magic = Short.toUnsignedInt(header.getShort(PageLayout.OFF_MAGIC));
        if (magic != PageLayout.MAGIC) {
            throw new PageLayoutException("bad page magic: 0x" + Integer.toHexString(magic));
        }
        PageType type = PageType.fromCode(header.get(PageLayout.OFF_PAGE_TYPE));
        if (type != PageType.HEAP_META) {
            throw new PageLayoutException("expected HEAP_META page, got " + type);
        }
    }

    private ByteBuffer buf() {
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }
}
