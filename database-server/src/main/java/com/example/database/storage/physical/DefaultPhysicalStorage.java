package com.example.database.storage.physical;

import com.example.database.storage.DataDirectory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Local-filesystem {@link PhysicalStorage}. Paths are relative to {@link DataDirectory#root()}.
 * <p>
 * This class only moves bytes. It does not know about tables, JSON, WAL, or SQL —
 * {@code CatalogStore} and later {@code TableStore} interpret the contents.
 */
public final class DefaultPhysicalStorage implements PhysicalStorage {

    // Table pages will be 16 KiB later (offset = pageId * pageSize). Catalog I/O
    // ignores this and reads/writes whole files.
    public static final int DEFAULT_PAGE_SIZE = 16 * 1024;

    private final Path root;
    private final int pageSize;

    public DefaultPhysicalStorage(DataDirectory dataDirectory) {
        this(dataDirectory, DEFAULT_PAGE_SIZE);
    }

    public DefaultPhysicalStorage(DataDirectory dataDirectory, int pageSize) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        // StorageEngine owns DataDirectory lifecycle; we only keep the root path.
        this.root = dataDirectory.root();
        this.pageSize = pageSize;
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    @Override
    public void create(String file) {
        Path path = resolve(file);
        try {
            // Empty file first so later write() is "replace contents of an existing file"
            // and cannot silently create a new name via a typo.
            Files.createFile(path);
        } catch (FileAlreadyExistsException e) {
            throw new PhysicalStorageException("file already exists: " + file, e);
        } catch (IOException e) {
            // Keep java.nio exceptions inside this layer so catalog/SQL never catch IOException.
            throw new PhysicalStorageException("failed to create " + file, e);
        }
    }

    @Override
    public void delete(String file) {
        Path path = resolve(file);
        try {
            // deleteIfExists is false when the path is already gone — treat that as an
            // error so DROP-style callers notice a missing file instead of succeeding.
            if (!Files.deleteIfExists(path)) {
                throw new PhysicalStorageException("file not found: " + file);
            }
        } catch (PhysicalStorageException e) {
            // Re-throw as-is so "file not found" is not wrapped as "failed to delete".
            throw e;
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to delete " + file, e);
        }
    }

    @Override
    public boolean exists(String file) {
        return Files.exists(resolve(file));
    }

    @Override
    public byte[] read(String file) {
        Path path = resolve(file);
        requireExists(file, path);
        try {
            // Whole-file read is the catalog path (rewrite a small JSON file).
            return Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            throw new PhysicalStorageException("file not found: " + file, e);
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to read " + file, e);
        }
    }

    @Override
    public void write(String file, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        Path path = resolve(file);
        requireExists(file, path);
        try {
            // TRUNCATE_EXISTING replaces the entire file. CREATE is omitted so we never
            // invent a file here — callers must create() first.
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (NoSuchFileException e) {
            throw new PhysicalStorageException("file not found: " + file, e);
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to write " + file, e);
        }
    }

    @Override
    public byte[] read(String file, long offset, int length) {
        Path path = resolve(file);
        requireExists(file, path);
        // RandomAccessFile can seek to a byte offset. That is how later page I/O
        // will work (offset = pageId * pageSize) without reading the whole file.
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = raf.length();
            requireValidRange(file, offset, length, fileLength);
            byte[] buffer = new byte[length];
            raf.seek(offset);
            // read() may return fewer bytes than requested; readFully fails if the
            // range we already validated cannot be filled (file shrunk under us).
            raf.readFully(buffer);
            return buffer;
        } catch (PhysicalStorageException e) {
            throw e;
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to read " + file, e);
        }
    }

    @Override
    public void write(String file, long offset, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        Path path = resolve(file);
        requireExists(file, path);
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            long fileLength = raf.length();
            // offset == fileLength is append (grow the file). offset > fileLength
            // would leave a hole of undefined zeros — reject that for now.
            if (offset < 0 || offset > fileLength) {
                throw new PhysicalStorageException("invalid offset " + offset + " for file " + file);
            }
            raf.seek(offset);
            raf.write(bytes);
        } catch (PhysicalStorageException e) {
            throw e;
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to write " + file, e);
        }
    }

    @Override
    public void flush(String file) {
        Path path = resolve(file);
        requireExists(file, path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            // write() can return after bytes sit only in the OS cache; a crash would lose them.
            // force(true) asks the OS to persist file contents and metadata (size, mtime).
            // This is not a transaction commit or WAL — only "persist this file now."
            channel.force(true);
        } catch (NoSuchFileException e) {
            throw new PhysicalStorageException("file not found: " + file, e);
        } catch (IOException e) {
            throw new PhysicalStorageException("failed to flush " + file, e);
        }
    }

    private Path resolve(String file) {
        Objects.requireNonNull(file, "file");
        if (file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        Path relative = Path.of(file);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("file must be relative: " + file);
        }
        // normalize() collapses ".." so we can test the final path against root.
        Path resolved = root.resolve(relative).normalize();
        // Callers pass names like "catalog.json". Reject ".." / absolute paths so
        // this layer cannot write outside the data directory.
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("file escapes data directory: " + file);
        }
        if (resolved.equals(root)) {
            throw new IllegalArgumentException("file must not be the data directory: " + file);
        }
        return resolved;
    }

    private static void requireExists(String file, Path path) {
        // A directory with the same name is not a storage file; treat it as missing
        // so we never read/write/flush a folder.
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new PhysicalStorageException("file not found: " + file);
        }
    }

    private static void requireValidRange(String file, long offset, int length, long fileLength) {
        // length > fileLength - offset avoids overflow from offset + length on huge files.
        if (offset < 0 || length < 0 || offset > fileLength || length > fileLength - offset) {
            throw new PhysicalStorageException(
                    "invalid offset " + offset + " length " + length + " for file " + file
            );
        }
    }
}
