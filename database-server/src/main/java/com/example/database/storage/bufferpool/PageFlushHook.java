package com.example.database.storage.bufferpool;

/**
 * Callback invoked immediately before a dirty frame is written to {@link com.example.database.storage.physical.PhysicalStorage}.
 * Used for index page WAL-before-data (I8).
 */
@FunctionalInterface
public interface PageFlushHook {

    void beforePageFlush(PageId pageId, byte[] pageBytes);
}
