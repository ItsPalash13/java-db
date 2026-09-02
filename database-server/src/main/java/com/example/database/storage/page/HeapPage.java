package com.example.database.storage.page;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.catalog.ColumnType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory view of one fixed-size slotted heap page.
 * <p>
 * Mutates a {@code byte[]} that can later be written at
 * {@code offset = pageId * pageSize}. Does not pin, latch, flush, or talk to
 * {@code PhysicalStorage} — those are BufferPool / FileTableStore concerns.
 * Deleted slots keep their index (length 0) so a Rid is not reused for a
 * different rowId until the table is rebuilt.
 */
public final class HeapPage {

    private final byte[] data;
    private final int pageSize;

    private HeapPage(byte[] data) {
        this.data = data;
        this.pageSize = data.length;
    }

    /**
     * Empty HEAP page: no slots, free space is everything after the header.
     */
    public static HeapPage createEmpty(int pageId, int pageSize) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be >= 0");
        }
        if (pageSize < PageLayout.HEADER_SIZE + PageLayout.SLOT_SIZE) {
            throw new IllegalArgumentException("pageSize too small: " + pageSize);
        }
        if (pageSize > 0xFFFF) {
            // lower/upper are u16 offsets into the page.
            throw new IllegalArgumentException("pageSize must fit in u16: " + pageSize);
        }
        byte[] data = new byte[pageSize];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(PageLayout.OFF_MAGIC, (short) PageLayout.MAGIC);
        buf.put(PageLayout.OFF_PAGE_TYPE, PageType.HEAP.code());
        buf.put(PageLayout.OFF_FLAGS, (byte) 0);
        buf.putInt(PageLayout.OFF_PAGE_ID, pageId);
        buf.putShort(PageLayout.OFF_SLOT_COUNT, (short) 0);
        buf.putShort(PageLayout.OFF_LOWER, (short) PageLayout.HEADER_SIZE);
        buf.putShort(PageLayout.OFF_UPPER, (short) pageSize);
        buf.putShort(PageLayout.OFF_PAD, (short) 0);
        buf.putLong(PageLayout.OFF_LSN_RESERVED, 0L);
        return new HeapPage(data);
    }

    /**
     * Wrap existing page bytes (e.g. after a disk read). Validates magic and type.
     */
    public static HeapPage wrap(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < PageLayout.HEADER_SIZE) {
            throw new PageLayoutException("page shorter than header: " + data.length);
        }
        HeapPage page = new HeapPage(data);
        page.validateHeader();
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    public int pageId() {
        return buf().getInt(PageLayout.OFF_PAGE_ID);
    }

    public int slotCount() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_SLOT_COUNT));
    }

    public int lower() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_LOWER));
    }

    public int upper() {
        return Short.toUnsignedInt(buf().getShort(PageLayout.OFF_UPPER));
    }

    /** Bytes available in {@code [lower, upper)} for a new slot + payload. */
    public int freeSpace() {
        return upper() - lower();
    }

    /**
     * Copy of the page image. Callers that pin a frame later must not keep a
     * pointer into the live array after unpin — this clone is safe to retain.
     */
    public byte[] toBytes() {
        return data.clone();
    }

    /** Live backing array for tests / future BufferPool frames. */
    public byte[] data() {
        return data;
    }

    /**
     * Append a slot and pack the row at the high end. Does not reuse tombstones
     * — a Rid must keep naming the same logical rowId.
     *
     * @return slotId of the new directory entry
     */
    public int insert(long rowId, Object[] values, ColumnType[] types) {
        byte[] payload = RowCodec.encode(rowId, values, types);
        int need = PageLayout.SLOT_SIZE + payload.length;
        if (freeSpace() < need) {
            throw new PageLayoutException(
                    "page " + pageId() + " has " + freeSpace() + " free bytes, need " + need);
        }
        int slotId = slotCount();
        int newLower = lower() + PageLayout.SLOT_SIZE;
        int newUpper = upper() - payload.length;
        System.arraycopy(payload, 0, data, newUpper, payload.length);
        writeSlot(slotId, newUpper, payload.length);
        ByteBuffer header = buf();
        header.putShort(PageLayout.OFF_SLOT_COUNT, (short) (slotId + 1));
        header.putShort(PageLayout.OFF_LOWER, (short) newLower);
        header.putShort(PageLayout.OFF_UPPER, (short) newUpper);
        return slotId;
    }

    public Optional<Tuple> read(int slotId, ColumnType[] types) {
        requireSlot(slotId);
        int length = slotLength(slotId);
        if (length == 0) {
            return Optional.empty();
        }
        int offset = slotOffset(slotId);
        byte[] payload = new byte[length];
        System.arraycopy(data, offset, payload, 0, length);
        return Optional.of(RowCodec.decode(payload, types));
    }

    /**
     * Overwrite in place when the new payload fits the old slot length.
     * Growing VARCHAR that no longer fits must be handled by the file heap
     * (delete + insert elsewhere) — Phase 2 codecs do not relocate.
     */
    public void update(int slotId, long rowId, Object[] values, ColumnType[] types) {
        requireSlot(slotId);
        int oldLength = slotLength(slotId);
        if (oldLength == 0) {
            throw new PageLayoutException("cannot update tombstone slot " + slotId);
        }
        byte[] payload = RowCodec.encode(rowId, values, types);
        if (payload.length > oldLength) {
            throw new PageLayoutException(
                    "updated row needs " + payload.length + " bytes, slot holds " + oldLength);
        }
        int offset = slotOffset(slotId);
        // Shorter payload leaves a hole at the end of the old slot; reclaim waits on compaction.
        System.arraycopy(payload, 0, data, offset, payload.length);
        writeSlot(slotId, offset, payload.length);
    }

    /** Mark the slot dead; Rid stays reserved. Does not shrink lower/upper. */
    public void delete(int slotId) {
        requireSlot(slotId);
        writeSlot(slotId, 0, 0);
    }

    public boolean isLive(int slotId) {
        requireSlot(slotId);
        return slotLength(slotId) != 0;
    }

    public List<Tuple> scanLive(ColumnType[] types) {
        List<Tuple> rows = new ArrayList<>();
        int n = slotCount();
        for (int slotId = 0; slotId < n; slotId++) {
            read(slotId, types).ifPresent(rows::add);
        }
        return rows;
    }

    private void validateHeader() {
        ByteBuffer header = buf();
        int magic = Short.toUnsignedInt(header.getShort(PageLayout.OFF_MAGIC));
        if (magic != PageLayout.MAGIC) {
            throw new PageLayoutException("bad page magic: 0x" + Integer.toHexString(magic));
        }
        PageType.fromCode(header.get(PageLayout.OFF_PAGE_TYPE));
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
}
