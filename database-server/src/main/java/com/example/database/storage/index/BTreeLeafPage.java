package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageLayoutException;
import com.example.database.storage.page.PageType;
import com.example.database.storage.page.Rid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * B+ tree leaf node: sorted {@code key → Rid} entries in a slotted page.
 * {@code nextLeafPageId} links leaf siblings for range scans (equality uses one page).
 */
public final class BTreeLeafPage {

    private final byte[] data;
    private final int pageSize;

    private BTreeLeafPage(byte[] data) {
        this.data = data;
        this.pageSize = data.length;
    }

    public static BTreeLeafPage createEmpty(int pageId, int pageSize) {
        if (pageSize < PageLayout.HEADER_SIZE + PageLayout.SLOT_SIZE) {
            throw new IllegalArgumentException("pageSize too small: " + pageSize);
        }
        byte[] bytes = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.INDEX_LEAF.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        buf.putShort(PageLayout.OFF_LOWER, (short) PageLayout.HEADER_SIZE);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putLong(PageLayout.OFF_LSN_RESERVED, 0L);
        buf.putInt(IndexPageLayout.OFF_LEAF_NEXT, -1);
        return new BTreeLeafPage(bytes);
    }

    public static BTreeLeafPage wrap(byte[] data) {
        Objects.requireNonNull(data, "data");
        BTreeLeafPage page = new BTreeLeafPage(data);
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

    public int nextLeafPageId() {
        return buf().getInt(IndexPageLayout.OFF_LEAF_NEXT);
    }

    public void setNextLeafPageId(int pageId) {
        buf().putInt(IndexPageLayout.OFF_LEAF_NEXT, pageId);
    }

    public int insertSorted(byte[] key, Rid rid, ColumnType[] types) {
        int slot = findInsertSlot(key, types);
        insertAt(slot, key, rid);
        return slot;
    }

    public void insertAt(int slotId, byte[] key, Rid rid) {
        byte[] payload = encodeEntry(key, rid);
        int need = PageLayout.SLOT_SIZE + payload.length;
        if (freeSpace() < need) {
            throw new PageLayoutException("leaf page " + pageId() + " needs " + need + " bytes, has " + freeSpace());
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

    public void deleteSlot(int slotId) {
        requireSlot(slotId);
        writeSlot(slotId, 0, 0);
    }

    public boolean isLive(int slotId) {
        requireSlot(slotId);
        return slotLength(slotId) != 0;
    }

    public Optional<LeafEntry> read(int slotId, ColumnType[] types) {
        requireSlot(slotId);
        int length = slotLength(slotId);
        if (length == 0) {
            return Optional.empty();
        }
        int offset = slotOffset(slotId);
        byte[] payload = new byte[length];
        System.arraycopy(data, offset, payload, 0, length);
        return Optional.of(decodeEntry(payload, types));
    }

    public List<LeafEntry> liveEntries(ColumnType[] types) {
        List<LeafEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < slotCount(); slot++) {
            read(slot, types).ifPresent(entries::add);
        }
        return entries;
    }

    public int findInsertSlot(byte[] key, ColumnType[] types) {
        int slots = slotCount();
        for (int slot = 0; slot < slots; slot++) {
            if (!isLive(slot)) {
                continue;
            }
            byte[] existing = keyBytes(slot);
            if (IndexKeyCodec.compare(key, existing, types) <= 0) {
                return slot;
            }
        }
        return slots;
    }

    public byte[] keyBytes(int slotId) {
        int length = slotLength(slotId);
        int keyLength = length - IndexPageLayout.RID_BYTES;
        byte[] key = new byte[keyLength];
        System.arraycopy(data, slotOffset(slotId), key, 0, keyLength);
        return key;
    }

    public Rid ridAt(int slotId) {
        int offset = slotOffset(slotId) + slotLength(slotId) - IndexPageLayout.RID_BYTES;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int page = buf.getInt(offset);
        int slot = buf.getInt(offset + Integer.BYTES);
        return new Rid(page, slot);
    }

    private static byte[] encodeEntry(byte[] key, Rid rid) {
        byte[] payload = new byte[key.length + IndexPageLayout.RID_BYTES];
        System.arraycopy(key, 0, payload, 0, key.length);
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buf.position(key.length);
        buf.putInt(rid.pageId());
        buf.putInt(rid.slotId());
        return payload;
    }

    private static LeafEntry decodeEntry(byte[] payload, ColumnType[] types) {
        int keyLength = payload.length - IndexPageLayout.RID_BYTES;
        byte[] key = new byte[keyLength];
        System.arraycopy(payload, 0, key, 0, keyLength);
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buf.position(keyLength);
        Rid rid = new Rid(buf.getInt(), buf.getInt());
        return new LeafEntry(key, rid, IndexKeyCodec.decode(key, types));
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
        if (type != PageType.INDEX_LEAF) {
            throw new PageLayoutException("expected INDEX_LEAF page, got " + type);
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

    public record LeafEntry(byte[] keyBytes, Rid rid, Object[] keyValues) {
    }
}
