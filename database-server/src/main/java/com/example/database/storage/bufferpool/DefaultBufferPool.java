package com.example.database.storage.bufferpool;

import com.example.database.storage.page.HeapPage;
import com.example.database.storage.physical.PhysicalStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-size frame table with clock (second-chance) replacement over {@link PhysicalStorage}.
 * <p>
 * Phase 3 policy: <b>do not evict dirty frames</b> (global no-steal until DML WAL).
 * Dirty pages leave RAM only via {@link #flush} / {@link #flushAll} (checkpoint /
 * {@code StorageEngine.stop}). Evicting a dirty page without a durable log would make a
 * crash show heap bytes with no WAL record — recovery could not undo/redo correctly.
 * <p>
 * Latches are per-frame read/write locks and are <em>not</em> {@code LockManager} locks.
 * Acquire order: pin, then latch; never hold a latch until COMMIT.
 * Constructed by {@code DefaultStorageEngine}; Volcano must not call pin.
 *
 * @see BufferPool for the pin → latch → mutate → unpin protocol and examples
 */
public final class DefaultBufferPool implements BufferPool {

    /** Default number of RAM frames when the engine does not override (tests may use 2). */
    public static final int DEFAULT_FRAME_COUNT = 64;

    private final PhysicalStorage storage;
    private final int pageSize;
    private final BufferFrame[] frames;
    private final ReentrantReadWriteLock[] latches;
    /** Guards frame table metadata (pin counts, clock, page→frame map). Not a page latch. */
    private final Object tableLock = new Object();
    private final Map<PageId, Integer> pageToFrame = new HashMap<>();
    private int clockHand;

    /** Builds a pool with {@link #DEFAULT_FRAME_COUNT} frames using {@code storage.pageSize()}. */
    public DefaultBufferPool(PhysicalStorage storage) {
        this(storage, DEFAULT_FRAME_COUNT);
    }

    /**
     * @param storage    offset I/O target; catalog/WAL must not go through this pool
     * @param frameCount fixed number of reusable frames (must be &gt;= 1)
     */
    public DefaultBufferPool(PhysicalStorage storage, int frameCount) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be >= 1");
        }
        this.pageSize = storage.pageSize();
        this.frames = new BufferFrame[frameCount];
        this.latches = new ReentrantReadWriteLock[frameCount];
        for (int i = 0; i < frameCount; i++) {
            frames[i] = new BufferFrame(i, pageSize);
            // fair=false: short critical sections; latch waits that look like lock waits are bugs.
            latches[i] = new ReentrantReadWriteLock();
        }
    }

    @Override
    public BufferFrame pin(PageId pageId) {
        Objects.requireNonNull(pageId, "pageId");
        synchronized (tableLock) {
            Integer existing = pageToFrame.get(pageId);
            if (existing != null) {
                BufferFrame frame = frames[existing];
                frame.incrementPin();
                return frame;
            }
            int victim = findCleanVictim();
            BufferFrame frame = frames[victim];
            evictIfOccupied(frame);
            long offset = (long) pageId.pageId() * pageSize;
            byte[] bytes = storage.read(pageId.file(), offset, pageSize);
            frame.assign(pageId, bytes);
            frame.incrementPin();
            pageToFrame.put(pageId, victim);
            return frame;
        }
    }

    @Override
    public BufferFrame newPage(String file) {
        Objects.requireNonNull(file, "file");
        synchronized (tableLock) {
            // Reserve a clean frame before growing the file — otherwise a failed
            // allocate would leave an orphan page on disk with no frame.
            int victim = findCleanVictim();
            BufferFrame frame = frames[victim];

            long length = storage.byteLength(file);
            if (length % pageSize != 0) {
                throw new BufferPoolException(
                        "file " + file + " length " + length + " is not a multiple of pageSize "
                                + pageSize);
            }
            if (length / pageSize > Integer.MAX_VALUE) {
                throw new BufferPoolException("file " + file + " has too many pages");
            }
            int pageIdNum = (int) (length / pageSize);
            PageId pageId = new PageId(file, pageIdNum);
            // Empty HEAP image so a later FileTableStore can wrap without a second format.
            // Index newPage will use a different PageType once IndexStore exists.
            byte[] empty = HeapPage.createEmpty(pageIdNum, pageSize).toBytes();
            storage.write(file, length, empty);

            evictIfOccupied(frame);
            frame.assign(pageId, empty);
            frame.incrementPin();
            // Append already wrote the header to disk; caller marks dirty on first mutation.
            pageToFrame.put(pageId, victim);
            return frame;
        }
    }

    @Override
    public void unpin(BufferFrame frame) {
        Objects.requireNonNull(frame, "frame");
        synchronized (tableLock) {
            requireOwned(frame);
            frame.decrementPin();
        }
    }

    @Override
    public void latchShared(BufferFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireOwnedUnlocked(frame);
        latches[frame.index()].readLock().lock();
    }

    @Override
    public void latchExclusive(BufferFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireOwnedUnlocked(frame);
        latches[frame.index()].writeLock().lock();
    }

    @Override
    public void unlatch(BufferFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ReentrantReadWriteLock latch = latches[frame.index()];
        if (latch.isWriteLockedByCurrentThread()) {
            latch.writeLock().unlock();
            return;
        }
        // Shared latch: unlock once. Holding write is already handled above.
        latch.readLock().unlock();
    }

    @Override
    public void markDirty(BufferFrame frame) {
        Objects.requireNonNull(frame, "frame");
        synchronized (tableLock) {
            requireOwned(frame);
            if (frame.pinCount() < 1) {
                throw new BufferPoolException("markDirty requires a pinned frame");
            }
            frame.markDirty();
        }
    }

    @Override
    public void flush(PageId pageId) {
        Objects.requireNonNull(pageId, "pageId");
        synchronized (tableLock) {
            Integer index = pageToFrame.get(pageId);
            if (index == null) {
                return;
            }
            BufferFrame frame = frames[index];
            if (frame.dirty()) {
                writeFrameToDisk(frame);
            }
        }
        storage.flush(pageId.file());
    }

    @Override
    public void flushAll() {
        Set<String> files = new HashSet<>();
        synchronized (tableLock) {
            for (BufferFrame frame : frames) {
                if (frame.occupied() && frame.dirty()) {
                    writeFrameToDisk(frame);
                    files.add(frame.pageId().file());
                }
            }
        }
        for (String file : files) {
            storage.flush(file);
        }
    }

    /** How many frames currently hold a page (test/diagnostics). */
    public int occupiedCount() {
        synchronized (tableLock) {
            int n = 0;
            for (BufferFrame frame : frames) {
                if (frame.occupied()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Total frame slots in this pool (fixed at construction). */
    public int frameCount() {
        return frames.length;
    }

    /** Page size copied from {@link PhysicalStorage#pageSize()} at construction. */
    public int pageSize() {
        return pageSize;
    }

    /** Write one dirty frame; Phase 6 will flush WAL up to this page's LSN first. */
    private void writeFrameToDisk(BufferFrame frame) {
        PageId id = frame.pageId();
        long offset = (long) id.pageId() * pageSize;
        // DML WAL-before-data (Phase 6) would flush the log up to this page's LSN first.
        storage.write(id.file(), offset, frame.data());
        frame.clearDirty();
    }

    /**
     * Drop a clean occupied frame from the page map so the slot can be reused.
     * Refuses dirty frames — that would be steal without WAL.
     */
    private void evictIfOccupied(BufferFrame frame) {
        if (!frame.occupied()) {
            return;
        }
        if (frame.dirty()) {
            // Should not happen: findCleanVictim only returns clean frames.
            throw new BufferPoolException("refusing to evict dirty page " + frame.pageId());
        }
        pageToFrame.remove(frame.pageId());
        frame.clear();
    }

    /**
     * Clock hand: skip pinned frames; clear reference bit once; only return clean unpinned
     * (or empty) slots. Preferring clean victims avoids flush-on-evict and enforces no-steal.
     *
     * @throws BufferPoolException if every frame is pinned or dirty-unpinned
     */
    private int findCleanVictim() {
        int n = frames.length;
        int examined = 0;
        // Two passes worth of slots so every frame can clear its reference bit once.
        while (examined < n * 2) {
            BufferFrame frame = frames[clockHand];
            int index = clockHand;
            clockHand = (clockHand + 1) % n;
            examined++;

            if (!frame.occupied()) {
                return index;
            }
            if (frame.pinCount() > 0) {
                continue;
            }
            if (frame.dirty()) {
                // Leave dirty unpinned pages in RAM until flushAll / explicit flush.
                continue;
            }
            if (frame.referenced()) {
                frame.clearReferenced();
                continue;
            }
            return index;
        }
        throw new BufferPoolException(
                "buffer pool exhausted: no clean unpinned frame (flush dirty pages or unpin)");
    }

    /** Validates frame identity under {@link #tableLock}. */
    private void requireOwned(BufferFrame frame) {
        if (frame.index() < 0 || frame.index() >= frames.length || frames[frame.index()] != frame) {
            throw new BufferPoolException("frame does not belong to this pool");
        }
        if (!frame.occupied()) {
            throw new BufferPoolException("frame is not occupied");
        }
    }

    /**
     * Same checks as {@link #requireOwned} but without taking {@link #tableLock}.
     * Latch acquisition must not hold the table lock (avoids lock-order cycles with waiters).
     */
    private void requireOwnedUnlocked(BufferFrame frame) {
        // Latch path must not take tableLock (avoid lock-order cycles with waiters).
        if (frame.index() < 0 || frame.index() >= frames.length || frames[frame.index()] != frame) {
            throw new BufferPoolException("frame does not belong to this pool");
        }
        if (!frame.occupied()) {
            throw new BufferPoolException("frame is not occupied");
        }
    }

    /*
     * Example — create file, new page, mutate, flush (two-frame pool for eviction tests):
     *
     *   PhysicalStorage storage = new DefaultPhysicalStorage(dataDir, 256);
     *   BufferPool pool = new DefaultBufferPool(storage, 2);
     *   storage.create("t.ibd");
     *   BufferFrame f = pool.newPage("t.ibd");
     *   pool.latchExclusive(f);
     *   f.data()[0] = 1;
     *   pool.markDirty(f);
     *   pool.unlatch(f);
     *   pool.unpin(f);
     *   pool.flushAll();   // dirty pages are not clock-evicted until this
     */
}
