package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageLayoutException;
import com.example.database.storage.page.PageType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * B+ tree internal node: separator keys and child page ids.
 * {@code leftChildPageId} is the subtree with keys less than the first separator.
 */
public final class BTreeInternalPage {

    private final byte[] data;
    private final int pageSize;

    private BTreeInternalPage(byte[] data) {
        this.data = data;
        this.pageSize = data.length;
    }

    public static BTreeInternalPage createEmpty(int pageId, int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + PageLayout.SLOT_SIZE) {
            throw new IllegalArgumentException("pageSize too small: " + pageSize);
        }
        byte[] bytes = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.INDEX_INTERNAL.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        buf.putShort(PageLayout.OFF_LOWER, (short) PageLayout.HEADER_SIZE);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putLong(PageLayout.OFF_LSN_RESERVED, 0L);
        buf.putInt(IndexPageLayout.OFF_INTERNAL_LEFT, -1);
        return new BTreeInternalPage(bytes);
    }

    public static BTreeInternalPage wrap(byte[] data) {
        Objects.requireNonNull(data, "data");
        BTreeInternalPage page = new BTreeInternalPage(data);
        page.validateHeader();
        return page;
    }

    public byte[] data() {
        return data;
    }

    public int pageId() {
        return buf().getInt(PageLayout.OFF_PAGE_ID);
    }

    public int slotCount() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_SLOT_COUNT));
    }

    public int freeSpace() {
        return upper() - lower();
    }

    public int leftChildPageId() {
        return buf().getInt(IndexPageLayout.OFF_INTERNAL_LEFT);
    }

    public void setLeftChildPageId(int pageId) {
        buf().putInt(IndexPageLayout.OFF_INTERNAL_LEFT, pageId);
    }

    public void insertSeparator(int slotId, byte[] key, int rightChildPageId) {
        byte[] payload = encodeEntry(key, rightChildPageId);
        int need = PageLayout.SLOT_SIZE + payload.length;
        if (freeSpace() < need) {
            throw new PageLayoutException(
                    "internal page " + pageId() + " needs " + need + " bytes, has " + freeSpace());
        }
        shiftSlotsRight(slotId);
        int newLower = lower() + PageLayout.SLOT_SIZE;
        int newUpper = upper() - payload.length;
        System.arraycopy(payload, 0, data, newUpper, payload.length);
        writeSlot(slotId, newUpper, payload.length);
        ByteBuffer header = buf();
        header.putShort(PageLayout.OFF_SLOT_COUNT, (short) (slotCount() + 1));
        header.putShort(PageLayout.OFF_LOWER, (short) newLower);
        header.putShort(PageLayout.OFF_UPPER, (short) newUpper);
    }

    public int childPageIdForKey(byte[] key, ColumnType[] types) {
        int child = leftChildPageId();
        for (int slot = 0; slot < slotCount(); slot++) {
            if (!isLive(slot)) {
                continue;
            }
            byte[] separator = keyBytes(slot);
            if (IndexKeyCodec.compare(key, separator, types) < 0) {
                return child;
            }
            child = rightChildAt(slot);
        }
        return child;
    }

    public int rightChildAt(int slotId) {
        int offset = slotOffset(slotId) + slotLength(slotId) - IndexPageLayout.CHILD_BYTES;
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    public byte[] keyBytes(int slotId) {
        int length = slotLength(slotId);
        int keyLength = length - IndexPageLayout.CHILD_BYTES;
        byte[] key = new byte[keyLength];
        System.arraycopy(data, slotOffset(slotId), key, 0, keyLength);
        return key;
    }

    public boolean isLive(int slotId) {
        requireSlot(slotId);
        return slotLength(slotId) != 0;
    }

    public void deleteSlot(int slotId) {
        requireSlot(slotId);
        writeSlot(slotId, 0, 0);
    }

    public void replaceSeparatorKey(int slotId, byte[] newKey) {
        requireSlot(slotId);
        int rightChild = rightChildAt(slotId);
        int length = slotLength(slotId);
        int offset = slotOffset(slotId);
        byte[] payload = encodeEntry(newKey, rightChild);
        if (payload.length != length) {
            throw new PageLayoutException("replaceSeparatorKey cannot resize slot on page " + pageId());
        }
        System.arraycopy(payload, 0, data, offset, payload.length);
    }

    public int liveSlotCount() {
        int live = 0;
        for (int slot = 0; slot < slotCount(); slot++) {
            if (isLive(slot)) {
                live++;
            }
        }
        return live;
    }

    public List<InternalEntry> liveEntries(ColumnType[] types) {
        List<InternalEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < slotCount(); slot++) {
            if (!isLive(slot)) {
                continue;
            }
            entries.add(new InternalEntry(keyBytes(slot), rightChildAt(slot)));
        }
        return entries;
    }

    private static byte[] encodeEntry(byte[] key, int childPageId) {
        byte[] payload = new byte[key.length + IndexPageLayout.CHILD_BYTES];
        System.arraycopy(key, 0, payload, 0, key.length);
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(key.length, childPageId);
        return payload;
    }

    private void shiftSlotsRight(int slotId) {
        int slots = slotCount();
        for (int i = slots; i > slotId; i--) {
            writeSlot(i, slotOffset(i - 1), slotLength(i - 1));
        }
    }

    private void validateHeader() {
        ByteBuffer header = buf();
        int magic = Short.toUnsignedInt(header.getShort(PageLayout.OFF_MAGIC));
        if (magic != PageLayout.MAGIC) {
            throw new PageLayoutException("bad page magic: 0x" + Integer.toHexString(magic));
        }
        PageType type = PageType.fromCode(header.get(PageLayout.OFF_PAGE_TYPE));
        if (type != PageType.INDEX_INTERNAL) {
            throw new PageLayoutException("expected INDEX_INTERNAL page, got " + type);
        }
        int lower = lower();
        int upper = upper();
        int slots = slotCount();
        if (lower < PageLayout.HEADER_SIZE || upper > pageSize || lower > upper) {
            throw new PageLayoutException("corrupt lower/upper: lower=" + lower + " upper=" + upper);
        }
        int expectedLower = PageLayout.HEADER_SIZE + slots * PageLayout.SLOT_SIZE;
        if (lower != expectedLower) {
            throw new PageLayoutException(
                    "lower " + lower + " != HEADER + slotCount*4 (" + expectedLower + ")");
        }
    }

    private void requireSlot(int slotId) {
        if (slotId < 0 || slotId >= slotCount()) {
            throw new PageLayoutException("slotId " + slotId + " out of range [0," + slotCount() + ")");
        }
    }

    private int lower() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_LOWER));
    }

    private int upper() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_UPPER));
    }

    private int slotOffset(int slotId) {
        return Short.toUnsignedInt(buf().getShort(slotDirOffset(slotId)));
    }

    private int slotLength(int slotId) {
        return Short.toUnsignedInt(buf().getShort(slotDirOffset(slotId) + 2));
    }

    private void writeSlot(int slotId, int offset, int length) {
        ByteBuffer header = buf();
        int dir = slotDirOffset(slotId);
        header.putShort(dir, (short) offset);
        header.putShort(dir + 2, (short) length);
    }

    private static int slotDirOffset(int slotId) {
        return PageLayout.HEADER_SIZE + slotId * PageLayout.SLOT_SIZE;
    }

    private ByteBuffer buf() {
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }

    public record InternalEntry(byte[] keyBytes, int rightChildPageId) {
    }
}
