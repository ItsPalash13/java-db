package com.example.database.storage.page;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.index.IndexMetaPage;
import com.example.database.storage.physical.PhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Startup check that every {@code .ibd} / {@code .idx} file matches
 * {@link PhysicalStorage#pageSize()}. Wrong {@code PAGE_SIZE} in {@code server.env}
 * must fail before catalog/WAL replay mutates anything.
 * <p>
 * Page 0 must be meta ({@link HeapMetaPage} / {@link IndexMetaPage}) with a stamped
 * page size equal to the configured value. Remaining pages are header-validated only.
 */
public final class PageFileValidator {

    private PageFileValidator() {
    }

    /**
     * Walk the data directory and validate every heap/index page file.
     *
     * @throws PhysicalStorageException if a file cannot be read as pages of {@code storage.pageSize()}
     */
    public static void validateAll(DataDirectory dataDirectory, PhysicalStorage storage) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(storage, "storage");
        Path root = dataDirectory.root();
        int pageSize = storage.pageSize();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(PageFileValidator::isPageFile)
                    .sorted()
                    .forEach(path -> validateFile(root, storage, path, pageSize));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan page files under " + root, e);
        }
    }

    private static boolean isPageFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".ibd") || name.endsWith(".idx");
    }

    private static void validateFile(Path root, PhysicalStorage storage, Path absolute, int pageSize) {
        String relative = toStoragePath(root, absolute);
        long length = storage.byteLength(relative);
        if (length == 0) {
            return;
        }
        if (length % pageSize != 0) {
            throw new PhysicalStorageException(
                    "page file " + relative + " length " + length
                            + " is not a multiple of PAGE_SIZE " + pageSize
                            + " — file was written with a different page size, or is corrupt"
            );
        }
        int pageCount = (int) (length / pageSize);
        boolean ibd = relative.endsWith(".ibd");
        for (int pageId = 0; pageId < pageCount; pageId++) {
            long offset = (long) pageId * pageSize;
            byte[] bytes;
            try {
                bytes = storage.read(relative, offset, pageSize);
            } catch (PhysicalStorageException e) {
                throw new PhysicalStorageException(
                        "cannot read " + relative + " page " + pageId
                                + " as PAGE_SIZE " + pageSize + ": " + e.getMessage(),
                        e
                );
            }
            try {
                if (pageId == 0) {
                    validateMetaPage(relative, ibd, bytes, pageSize);
                } else {
                    validateDataPageHeader(relative, pageId, bytes, pageSize, ibd);
                }
            } catch (PageLayoutException e) {
                throw new PhysicalStorageException(
                        "cannot interpret " + relative + " page " + pageId
                                + " with PAGE_SIZE " + pageSize + ": " + e.getMessage(),
                        e
                );
            }
        }
    }

    private static void validateMetaPage(String file, boolean ibd, byte[] data, int pageSize) {
        if (ibd) {
            HeapMetaPage meta = HeapMetaPage.wrap(data);
            requireStampedPageSize(file, meta.pageSize(), pageSize);
            return;
        }
        IndexMetaPage meta = IndexMetaPage.wrap(data);
        requireStampedPageSize(file, meta.pageSize(), pageSize);
    }

    private static void requireStampedPageSize(String file, int stamped, int expected) {
        if (stamped == 0) {
            throw new PhysicalStorageException(
                    "page file " + file + " meta is missing stamped PAGE_SIZE"
            );
        }
        if (stamped != expected) {
            throw new PhysicalStorageException(
                    "page file " + file + " stamped PAGE_SIZE " + stamped
                            + " does not match server PAGE_SIZE " + expected
            );
        }
    }

    private static void validateDataPageHeader(
            String file,
            int expectedPageId,
            byte[] data,
            int pageSize,
            boolean ibd
    ) {
        if (data.length != pageSize) {
            throw new PageLayoutException(
                    "read " + data.length + " bytes, expected PAGE_SIZE " + pageSize
            );
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int magic = Short.toUnsignedInt(header.getShort(PageLayout.OFF_MAGIC));
        if (magic != PageLayout.MAGIC) {
            throw new PageLayoutException("bad page magic: 0x" + Integer.toHexString(magic));
        }
        PageType type = PageType.fromCode(header.get(PageLayout.OFF_PAGE_TYPE));
        if (ibd) {
            if (type != PageType.HEAP) {
                throw new PageLayoutException("expected HEAP data page, got " + type);
            }
        } else if (type != PageType.INDEX_LEAF && type != PageType.INDEX_INTERNAL) {
            throw new PageLayoutException("expected INDEX_LEAF/INTERNAL, got " + type);
        }
        int pageId = header.getInt(PageLayout.OFF_PAGE_ID);
        if (pageId != expectedPageId) {
            throw new PageLayoutException(
                    "pageId in header is " + pageId + ", expected " + expectedPageId
                            + " — likely wrong PAGE_SIZE for " + file
            );
        }
        int slotCount = Short.toUnsignedInt(header.getShort(PageLayout.OFF_SLOT_COUNT));
        int lower = Short.toUnsignedInt(header.getShort(PageLayout.OFF_LOWER));
        int upper = Short.toUnsignedInt(header.getShort(PageLayout.OFF_UPPER));
        if (lower < PageLayout.HEADER_SIZE || upper > pageSize || lower > upper) {
            throw new PageLayoutException(
                    "corrupt lower/upper: lower=" + lower + " upper=" + upper
                            + " pageSize=" + pageSize
            );
        }
        int expectedLower = PageLayout.HEADER_SIZE + slotCount * PageLayout.SLOT_SIZE;
        if (lower != expectedLower) {
            throw new PageLayoutException(
                    "lower " + lower + " != HEADER + slotCount*4 (" + expectedLower + ")"
            );
        }
        for (int slot = 0; slot < slotCount; slot++) {
            int dir = PageLayout.HEADER_SIZE + slot * PageLayout.SLOT_SIZE;
            int offset = Short.toUnsignedInt(header.getShort(dir));
            int length = Short.toUnsignedInt(header.getShort(dir + 2));
            if (length == 0) {
                continue;
            }
            if (offset < upper || offset + length > pageSize) {
                throw new PageLayoutException(
                        "slot " + slot + " payload [" + offset + "," + (offset + length)
                                + ") outside [upper=" + upper + ", pageSize=" + pageSize + ")"
                );
            }
        }
    }

    private static String toStoragePath(Path root, Path absolute) {
        String relative = root.relativize(absolute).toString();
        return relative.replace('\\', '/');
    }
}
