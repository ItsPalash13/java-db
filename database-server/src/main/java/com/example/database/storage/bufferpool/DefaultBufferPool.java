package com.example.database.storage.bufferpool;

import com.example.database.storage.index.EmptyPageFactory;
import com.example.database.storage.page.HeapPage;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageType;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.wal.WALManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-size frame table with clock (second-chance) replacement over {@link PhysicalStorage}.
 * <p>
 * Phase 3/6 policy: <b>do not evict dirty frames</b> (global no-steal). Dirty pages leave
 * RAM via {@link #flush} / {@link #flushAll}. Before writing an {@code .ibd} frame,
 * {@link WALManager#flushUpTo} forces the covering WAL LSN (WAL-before-data).
 * <p>
 * Latches are per-frame read/write locks and are <em>not</em> {@code LockManager} locks.
 */
public final class DefaultBufferPool implements BufferPool {

    /** Default RAM frames. Kept modest: fat keys ({@code INDEX_KEY_PADDING_BYTES}) create
     * many dirty .idx pages under no-steal. {@code load_1k.txt} issues CHECKPOINT every 100
     * inserts so {@link #flushAll} clears dirty bits. 128 leaves headroom for two indexes
     * (pk + secondary) between checkpoints — not a substitute for those CHECKPOINTs. */
    public static final int DEFAULT_FRAME_COUNT = 128;

    private final PhysicalStorage storage;
    private final int pageSize;
    private final BufferFrame[] frames;
    private final ReentrantReadWriteLock[] latches;
    /** Guards frame table metadata (pin counts, clock, page→frame map). Not a page latch. */
    private final Object tableLock = new Object();
    private final Map<PageId, Integer> pageToFrame = new HashMap<>();
    private int clockHand;
    private PageFlushHook pageFlushHook;
    private WALManager walManager;

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

    /** Optional hook for index page WAL-before-data (I8). */
    public void setPageFlushHook(PageFlushHook hook) {
        this.pageFlushHook = hook;
    }

    /**
     * WAL gate for heap pages: {@link #writeFrameToDisk} calls {@link WALManager#flushUpTo}
     * before writing {@code .ibd} bytes. Index pages use {@link PageFlushHook} only.
     */
    public void setWalManager(WALManager walManager) {
        this.walManager = walManager;
    }

    /** Write one dirty frame; WAL-before-data for heap, IndexPageWal hook for {@code .idx}. */
    private void writeFrameToDisk(BufferFrame frame) {
        PageId id = frame.pageId();
        // Heap: force WAL through page LSN before data bytes hit disk (no-force COMMIT safe).
        if (walManager != null && id.file().endsWith(".ibd")) {
            long pageLsn = ByteBuffer.wrap(frame.data())
                    .order(ByteOrder.BIG_ENDIAN)
                    .getLong(PageLayout.OFF_LSN_RESERVED);
            if (pageLsn > 0) {
                walManager.flushUpTo(pageLsn);
            }
        }
        if (pageFlushHook != null) {
            pageFlushHook.beforePageFlush(id, frame.data());
        }
        long offset = (long) id.pageId() * pageSize;
        storage.write(id.file(), offset, frame.data());
        frame.clearDirty();
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
        return newPage(file, PageType.HEAP);
    }

    @Override
    public BufferFrame newPage(String file, PageType type) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(type, "type");
        synchronized (tableLock) {
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
            byte[] empty = EmptyPageFactory.emptyPage(pageIdNum, pageSize, type);
            storage.write(file, length, empty);

            evictIfOccupied(frame);
            frame.assign(pageId, empty);
            frame.incrementPin();
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
                // Typical cause with INDEX_KEY_PADDING_BYTES: too many dirty .idx pages.
                // Fix: CHECKPOINT (flushAll) during the load — see input/cmds/load_1k.txt.
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
