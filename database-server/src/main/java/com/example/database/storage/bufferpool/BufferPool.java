package com.example.database.storage.bufferpool;

/**
 * RAM cache of fixed-size disk pages for heap {@code .ibd} and index {@code .idx} files.
 * Callers {@link #pin} so a frame cannot be evicted, {@link #latchShared}/{@link #latchExclusive}
 * while touching bytes, then {@link #unpin}. Dirty frames reach disk only through
 * {@link #flush}/{@link #flushAll} (via {@code PhysicalStorage} offset I/O).
 * <p>
 * Not a lock manager ({@code LockManager}). Not a page-format codec ({@code HeapPage} /
 * later index pages). Catalog JSON and {@code wal.log} stay off this pool.
 * <p>
 * Acquire order: pin, then latch. Never hold a latch until COMMIT — that would serialize
 * every session that needs the page and invert lock/latch layers.
 *
 * <pre>
 *   // File must already exist (PhysicalStorage.create).
 *   BufferFrame f = pool.newPage("shop/users/users.ibd");
 *   pool.latchExclusive(f);
 *   f.data()[100] = 42;
 *   pool.markDirty(f);
 *   pool.unlatch(f);
 *   pool.unpin(f);
 *   pool.flush(f.pageId());
 * </pre>
 */
public interface BufferPool {

    /**
     * Load or hit {@code pageId} and increment pin count so the frame cannot be evicted.
     * On a miss, may evict a <em>clean</em> unpinned victim (Phase 3 never evicts dirty
     * frames). Caller must {@link #unpin} when done.
     * <p>
     * This is not a SQL lock — use {@code LockManager} for table/row concurrency; latch
     * separately while touching {@link BufferFrame#data()}.
     *
     * @throws BufferPoolException if no clean unpinned frame is available
     */
    BufferFrame pin(PageId pageId);

    /**
     * Append an empty page at the end of {@code file}, install it in a frame, and pin it.
     * {@code pageId} is {@code byteLength(file) / pageSize}. File must already exist
     * ({@code PhysicalStorage.create}). Reserves a frame <em>before</em> growing the file
     * so a failed allocate cannot leave an orphan page on disk.
     * <p>
     * Phase 3 writes an empty {@code HeapPage} image; index {@code newPage} will use a
     * different {@code PageType} once IndexStore exists.
     *
     * @return pinned frame for the new page (pinCount = 1); caller must unpin
     * @throws BufferPoolException if the pool is exhausted or file length is not page-aligned
     */
    BufferFrame newPage(String file);

    /**
     * Decrement pin count. At 0 the frame may be chosen as a clock victim (if clean).
     * Missing unpin fills the pool with immortal pages and the next pin fails.
     */
    void unpin(BufferFrame frame);

    /**
     * Shared latch: many threads may read this frame's bytes concurrently.
     * Hold only while copying or inspecting; unlatch before waiting on SQL locks or
     * returning a tuple up the Volcano pipeline.
     */
    void latchShared(BufferFrame frame);

    /**
     * Exclusive latch: one writer; no concurrent readers.
     * Required before mutating {@link BufferFrame#data()} and before {@link #markDirty}.
     */
    void latchExclusive(BufferFrame frame);

    /**
     * Release the shared or exclusive latch held by the current thread on this frame.
     */
    void unlatch(BufferFrame frame);

    /**
     * Mark the RAM copy different from disk. Call under exclusive latch after a write.
     * Dirty frames are not clock-evicted until {@link #flush} / {@link #flushAll}
     * (no-steal until DML WAL exists).
     */
    void markDirty(BufferFrame frame);

    /**
     * If {@code pageId} is resident and dirty, write it to disk then
     * {@code PhysicalStorage.flush} that file. No-op if the page is not in the pool.
     * Until Phase 6 there is no WAL-before-data wait.
     */
    void flush(PageId pageId);

    /**
     * Write every dirty frame and flush each touched file.
     * Used on clean shutdown ({@code StorageEngine.stop}) and later on CHECKPOINT.
     */
    void flushAll();
}
