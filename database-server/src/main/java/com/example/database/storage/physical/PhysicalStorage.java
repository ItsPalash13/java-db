package com.example.database.storage.physical;

/**
 * Lowest-level persistence: files, offsets, and bytes under the data directory.
 * No catalog, table, JSON, WAL, or SQL semantics.
 */
public interface PhysicalStorage {

    /** Configurable page size in bytes. Unused by catalog whole-file I/O. */
    int pageSize();

    void create(String file);

    void delete(String file);

    boolean exists(String file);

    /** Entire file contents. */
    byte[] read(String file);

    /** Replace entire file contents. File must already exist. */
    void write(String file, byte[] bytes);

    /** {@code length} bytes starting at {@code offset}. */
    byte[] read(String file, long offset, int length);

    /** Overwrite bytes at {@code offset}. Offset must be within {@code [0, fileLength]}. */
    void write(String file, long offset, byte[] bytes);

    /** Request that buffered writes for this file reach durable storage. */
    void flush(String file);
}
