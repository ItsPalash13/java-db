package com.example.database.storage.index;

import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageLayoutException;
import com.example.database.storage.page.PageType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Page 0 of every {@code .idx} file: durable root pointer, tree height,
 * {@code PAGE_SIZE}, and {@code INDEX_KEY_PADDING_BYTES}.
 * Not a B+ tree node — data pages start at page 1.
 */
public final class IndexMetaPage {

    private final byte[] data;

    private IndexMetaPage(byte[] data) {
        this.data = data;
    }

    public static IndexMetaPage createEmpty(int pageId, int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + IndexPageLayout.META_PAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("pageSize too small for index meta: " + pageSize);
        }
        byte[] bytes = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.INDEX_META.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        // lower past pageSize + keyPadding stamps (root/height live in LSN-reserved region).
        int lower = PageLayout.HEADER_SIZE + IndexPageLayout.META_PAGE_SIZE_BYTES;
        buf.putShort(PageLayout.OFF_LOWER, (short) lower);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putInt(IndexPageLayout.OFF_META_ROOT, -1);
        buf.putInt(IndexPageLayout.OFF_META_HEIGHT, 0);
        buf.putInt(IndexPageLayout.OFF_META_PAGE_SIZE, pageSize);
        buf.putInt(IndexPageLayout.OFF_META_KEY_PADDING, 0);
        return new IndexMetaPage(bytes);
    }

    public static IndexMetaPage wrap(byte[] data) {
        Objects.requireNonNull(data, "data");
        IndexMetaPage page = new IndexMetaPage(data);
        page.validateHeader();
        return page;
    }

    public byte[] data() {
        return data;
    }

    public byte[] toBytes() {
        return data.clone();
    }

    public int rootPageId() {
        return buf().getInt(IndexPageLayout.OFF_META_ROOT);
    }

    public int height() {
        return buf().getInt(IndexPageLayout.OFF_META_HEIGHT);
    }

    /** Page size this {@code .idx} was written with (must match server {@code PAGE_SIZE}). */
    public int pageSize() {
        return buf().getInt(IndexPageLayout.OFF_META_PAGE_SIZE);
    }

    /** Trailing key pad stamped at create time (must match {@code INDEX_KEY_PADDING_BYTES}). */
    public int keyPaddingBytes() {
        return buf().getInt(IndexPageLayout.OFF_META_KEY_PADDING);
    }

    public void setRoot(int rootPageId, int height) {
        ByteBuffer header = buf();
        header.putInt(IndexPageLayout.OFF_META_ROOT, rootPageId);
        header.putInt(IndexPageLayout.OFF_META_HEIGHT, height);
    }

    public void setPageSize(int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + IndexPageLayout.META_PAGE_SIZE_BYTES || pageSize > 0xFFFF) {
            throw new PageLayoutException("invalid stamped pageSize: " + pageSize);
        }
        buf().putInt(IndexPageLayout.OFF_META_PAGE_SIZE, pageSize);
    }

    public void setKeyPaddingBytes(int paddingBytes) {
        if (paddingBytes < 0 || paddingBytes > 0x8000) {
            throw new PageLayoutException("invalid stamped key padding: " + paddingBytes);
        }
        buf().putInt(IndexPageLayout.OFF_META_KEY_PADDING, paddingBytes);
    }

    private void validateHeader() {
        ByteBuffer header = buf();
        int magic = Short.toUnsignedInt(header.getShort(PageLayout.OFF_MAGIC));
        if (magic != PageLayout.MAGIC) {
            throw new PageLayoutException("bad page magic: 0x" + Integer.toHexString(magic));
        }
        PageType type = PageType.fromCode(header.get(PageLayout.OFF_PAGE_TYPE));
        if (type != PageType.INDEX_META) {
            throw new PageLayoutException("expected INDEX_META page, got " + type);
        }
    }

    private ByteBuffer buf() {
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }
}
