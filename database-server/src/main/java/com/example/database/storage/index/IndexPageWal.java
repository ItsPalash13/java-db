package com.example.database.storage.index;

import com.example.database.storage.bufferpool.PageId;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Append-only WAL for {@code .idx} page images. Logged before data pages are written
 * so a crash can restore index tree pages on restart (I8, coordinated with buffer-pool flush).
 */
public final class IndexPageWal {

    public static final String WAL_FILE = "index-wal.log";

    private final PhysicalStorage storage;

    public IndexPageWal(PhysicalStorage storage) {
        this.storage = storage;
    }

    /** WAL-before-data: append full page image, then caller may write the data file. */
    public void logPageWrite(PageId pageId, byte[] pageBytes) {
        if (!pageId.file().endsWith(".idx")) {
            return;
        }
        String line = pageId.file()
                + "\t"
                + pageId.pageId()
                + "\t"
                + Base64.getEncoder().encodeToString(pageBytes)
                + "\n";
        try {
            if (!storage.exists(WAL_FILE)) {
                storage.create(WAL_FILE);
            }
            storage.write(WAL_FILE, storage.byteLength(WAL_FILE), line.getBytes(StandardCharsets.UTF_8));
            storage.flush(WAL_FILE);
        } catch (PhysicalStorageException e) {
            throw new IndexStoreException("failed to append index page WAL", e);
        }
    }

    /** Replays all logged page images onto their {@code .idx} files (idempotent overwrite). */
    public void replay() {
        if (!storage.exists(WAL_FILE)) {
            return;
        }
        try {
            byte[] bytes = storage.read(WAL_FILE);
            if (bytes.length == 0) {
                return;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    continue;
                }
                String file = parts[0];
                if (!storage.exists(file)) {
                    continue;
                }
                int pageId = Integer.parseInt(parts[1]);
                byte[] page = Base64.getDecoder().decode(parts[2]);
                long offset = (long) pageId * page.length;
                storage.write(file, offset, page);
                storage.flush(file);
            }
        } catch (PhysicalStorageException e) {
            throw new IndexStoreException("failed to replay index page WAL", e);
        }
    }
}
