package com.example.database.storage.index;

import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageLayoutException;
import com.example.database.storage.page.PageType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Page 0 of every {@code .idx} file: durable root pointer and tree height.
 * Not a B+ tree node — data pages start at page 1.
 */
public final class IndexMetaPage {

    private final byte[] data;

    private IndexMetaPage(byte[] data) {
        this.data = data;
    }

    public static IndexMetaPage createEmpty(int pageId, int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + 8) {
            throw new IllegalArgumentException("pageSize too small for index meta: " + pageSize);
        }
        byte[] bytes = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.INDEX_META.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        buf.putShort(PageLayout.OFF_LOWER, (short) PageLayout.HEADER_SIZE);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putInt(IndexPageLayout.OFF_META_ROOT, -1);
        buf.putInt(IndexPageLayout.OFF_META_HEIGHT, 0);
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

    public void setRoot(int rootPageId, int height) {
        ByteBuffer header = buf();
        header.putInt(IndexPageLayout.OFF_META_ROOT, rootPageId);
        header.putInt(IndexPageLayout.OFF_META_HEIGHT, height);
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
