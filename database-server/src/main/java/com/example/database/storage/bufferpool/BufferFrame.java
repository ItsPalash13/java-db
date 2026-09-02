package com.example.database.storage.bufferpool;

/**
 * One RAM slot in the buffer pool holding a copy of a disk page.
 * <p>
 * Callers must {@link BufferPool#pin} before use and hold a shared/exclusive latch while
 * reading or writing {@link #data()}. Do not keep a pointer into {@link #data()} after
 * {@link BufferPool#unpin} — the clock may reuse this frame for another page (use-after-free).
 * <p>
 * Metadata here ({@code pinCount}, dirty, referenced) is owned by {@link DefaultBufferPool};
 * package-private mutators are not a public API for FileTableStore.
 *
 * <pre>
 *   BufferFrame f = pool.pin(new PageId("t.ibd", 0));
 *   pool.latchShared(f);
 *   byte b = f.data()[0];   // OK: pinned + latched
 *   pool.unlatch(f);
 *   pool.unpin(f);
 *   // f.data() must not be used after unpin
 * </pre>
 */
public final class BufferFrame {

    private final int index;
    private PageId pageId;
    private final byte[] data;
    private int pinCount;
    private boolean dirty;
    private boolean referenced;
    private boolean occupied;

    /**
     * Allocates an empty frame of {@code pageSize} bytes at slot {@code index} in the pool array.
     */
    BufferFrame(int index, int pageSize) {
        this.index = index;
        this.data = new byte[pageSize];
    }

    /** Index of this slot in the pool's frame array (stable for the process lifetime). */
    public int index() {
        return index;
    }

    /** Disk identity currently loaded into this frame, or {@code null} if free. */
    public PageId pageId() {
        return pageId;
    }

    /**
     * Live page bytes. Safe to read/write only while this frame is pinned and
     * latched by the calling thread. Length equals {@code PhysicalStorage.pageSize()}.
     */
    public byte[] data() {
        return data;
    }

    /** How many pin calls still hold this frame; 0 means eligible for clock eviction if clean. */
    public int pinCount() {
        return pinCount;
    }

    /** {@code true} if RAM differs from the last durable image on disk. */
    public boolean dirty() {
        return dirty;
    }

    /** {@code true} if this frame currently holds a page (not a free slot). */
    public boolean occupied() {
        return occupied;
    }

    int pageSize() {
        return data.length;
    }

    /**
     * Install {@code pageBytes} as the contents of this frame for {@code pageId}.
     * Resets pin/dirty; sets the clock referenced bit so a fresh load is not evicted immediately.
     */
    void assign(PageId pageId, byte[] pageBytes) {
        if (pageBytes.length != data.length) {
            throw new BufferPoolException(
                    "page bytes length " + pageBytes.length + " != frame size " + data.length);
        }
        this.pageId = pageId;
        System.arraycopy(pageBytes, 0, data, 0, data.length);
        this.pinCount = 0;
        this.dirty = false;
        this.referenced = true;
        this.occupied = true;
    }

    /** Mark the slot free after a clean eviction (page map entry already removed). */
    void clear() {
        this.pageId = null;
        this.pinCount = 0;
        this.dirty = false;
        this.referenced = false;
        this.occupied = false;
    }

    void incrementPin() {
        pinCount++;
        referenced = true;
    }

    void decrementPin() {
        if (pinCount <= 0) {
            throw new BufferPoolException("unpin of frame with pinCount 0");
        }
        pinCount--;
    }

    void markDirty() {
        dirty = true;
        referenced = true;
    }

    void clearDirty() {
        dirty = false;
    }

    /** Clock second-chance bit: recently used frames get one pass before eviction. */
    boolean referenced() {
        return referenced;
    }

    void clearReferenced() {
        referenced = false;
    }

    void setReferenced() {
        referenced = true;
    }
}
