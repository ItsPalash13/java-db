package com.example.database.storage.physical;

import com.example.database.storage.DataDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPhysicalStorageTest {

    @TempDir
    Path tempDir;

    private DefaultPhysicalStorage storage;

    @BeforeEach
    void setUp() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("store"));
        dataDirectory.ensureExists();
        storage = new DefaultPhysicalStorage(dataDirectory);
    }

    @Test
    void createWriteReadFlushDelete() {
        storage.create("catalog.json");
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        storage.write("catalog.json", bytes);
        storage.flush("catalog.json");

        assertTrue(storage.exists("catalog.json"));
        assertArrayEquals(bytes, storage.read("catalog.json"));

        storage.delete("catalog.json");
        assertFalse(storage.exists("catalog.json"));
    }

    @Test
    void createRejectsExistingFile() {
        storage.create("a.bin");
        PhysicalStorageException ex = assertThrows(
                PhysicalStorageException.class,
                () -> storage.create("a.bin")
        );
        assertEquals("file already exists: a.bin", ex.getMessage());
    }

    @Test
    void readWriteDeleteMissingFile() {
        assertEquals(
                "file not found: missing.bin",
                assertThrows(PhysicalStorageException.class, () -> storage.read("missing.bin")).getMessage()
        );
        assertEquals(
                "file not found: missing.bin",
                assertThrows(
                        PhysicalStorageException.class,
                        () -> storage.write("missing.bin", new byte[] {1})
                ).getMessage()
        );
        assertEquals(
                "file not found: missing.bin",
                assertThrows(PhysicalStorageException.class, () -> storage.delete("missing.bin")).getMessage()
        );
        assertFalse(storage.exists("missing.bin"));
    }

    @Test
    void wholeWriteReplacesContents() {
        storage.create("a.bin");
        storage.write("a.bin", new byte[] {1, 2, 3, 4});
        storage.write("a.bin", new byte[] {9, 8});
        assertArrayEquals(new byte[] {9, 8}, storage.read("a.bin"));
    }

    @Test
    void offsetReadAndWrite() {
        storage.create("a.bin");
        storage.write("a.bin", new byte[] {0, 1, 2, 3, 4, 5});

        storage.write("a.bin", 2L, new byte[] {8, 9});

        assertArrayEquals(new byte[] {0, 1, 8, 9, 4, 5}, storage.read("a.bin"));
        assertArrayEquals(new byte[] {8, 9, 4}, storage.read("a.bin", 2L, 3));
    }

    @Test
    void offsetWriteCanAppendAtEnd() {
        storage.create("a.bin");
        storage.write("a.bin", new byte[] {1, 2});
        storage.write("a.bin", 2L, new byte[] {3, 4});
        assertArrayEquals(new byte[] {1, 2, 3, 4}, storage.read("a.bin"));
    }

    @Test
    void offsetReadRejectsOutOfRange() {
        storage.create("a.bin");
        storage.write("a.bin", new byte[] {1, 2, 3});

        assertThrows(PhysicalStorageException.class, () -> storage.read("a.bin", -1L, 1));
        assertThrows(PhysicalStorageException.class, () -> storage.read("a.bin", 2L, 2));
        assertThrows(PhysicalStorageException.class, () -> storage.write("a.bin", 4L, new byte[] {9}));
    }

    @Test
    void rejectsPathEscapeAndAbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> storage.create("../outside.bin"));
        assertThrows(IllegalArgumentException.class, () -> storage.exists(""));
        Path absolute = tempDir.resolve("abs.bin");
        assertThrows(IllegalArgumentException.class, () -> storage.create(absolute.toString()));
    }

    @Test
    void defaultPageSizeIs16Kb() {
        assertEquals(DefaultPhysicalStorage.DEFAULT_PAGE_SIZE, storage.pageSize());
        assertEquals(16384, storage.pageSize());
    }

    @Test
    void customPageSize() {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("paged"));
        dataDirectory.ensureExists();
        DefaultPhysicalStorage paged = new DefaultPhysicalStorage(dataDirectory, 4096);
        assertEquals(4096, paged.pageSize());
    }

    @Test
    void createListAndDeleteEmptyDirectory() {
        storage.createDirectory("shop");
        assertTrue(storage.exists("shop"));
        assertEquals(List.of("shop"), storage.listDirectories(""));

        storage.deleteDirectory("shop");
        assertFalse(storage.exists("shop"));
        assertTrue(storage.listDirectories("").isEmpty());
    }

    @Test
    void listDirectoriesIgnoresFilesAtRoot() {
        storage.create("catalog.json");
        storage.createDirectory("shop");
        assertEquals(List.of("shop"), storage.listDirectories(""));
    }

    @Test
    void deleteDirectoryRejectsMissingAndNonEmpty() {
        assertEquals(
                "directory not found: missing",
                assertThrows(PhysicalStorageException.class, () -> storage.deleteDirectory("missing")).getMessage()
        );

        storage.createDirectory("shop");
        storage.create("shop/note.txt");
        assertEquals(
                "directory is not empty: shop",
                assertThrows(PhysicalStorageException.class, () -> storage.deleteDirectory("shop")).getMessage()
        );
        assertTrue(storage.exists("shop"));
    }

    @Test
    void directoryOpsRejectPathEscape() {
        assertThrows(IllegalArgumentException.class, () -> storage.createDirectory("../outside"));
        assertThrows(IllegalArgumentException.class, () -> storage.listDirectories(".."));
    }
}
