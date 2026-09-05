package com.example.database.storage.page;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.index.BTreeLeafPage;
import com.example.database.storage.index.IndexMetaPage;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageFileValidatorTest {

    private static final int PAGE_SIZE = 512;

    @TempDir
    Path tempDir;

    private DataDirectory dataDirectory;
    private DefaultPhysicalStorage storage;

    @BeforeEach
    void setUp() {
        dataDirectory = new DataDirectory(tempDir.resolve("data"));
        dataDirectory.ensureExists();
        storage = new DefaultPhysicalStorage(dataDirectory, PAGE_SIZE);
        storage.createDirectory("shop");
        storage.createDirectory("shop/users");
    }

    @Test
    void acceptsAlignedHeapAndIndexFiles() {
        storage.create("shop/users/users.ibd");
        storage.write("shop/users/users.ibd", 0, HeapMetaPage.createEmpty(0, PAGE_SIZE).toBytes());
        storage.write("shop/users/users.ibd", PAGE_SIZE, HeapPage.createEmpty(1, PAGE_SIZE).toBytes());

        storage.create("shop/users/name.idx");
        storage.write("shop/users/name.idx", 0, IndexMetaPage.createEmpty(0, PAGE_SIZE).toBytes());
        storage.write("shop/users/name.idx", PAGE_SIZE, BTreeLeafPage.createEmpty(1, PAGE_SIZE).data());

        assertDoesNotThrow(() -> PageFileValidator.validateAll(dataDirectory, storage));
    }

    @Test
    void rejectsLengthNotMultipleOfPageSize() {
        storage.create("shop/users/users.ibd");
        storage.write("shop/users/users.ibd", 0, new byte[PAGE_SIZE + 7]);

        PhysicalStorageException ex = assertThrows(
                PhysicalStorageException.class,
                () -> PageFileValidator.validateAll(dataDirectory, storage)
        );
        assertTrue(ex.getMessage().contains("PAGE_SIZE"));
        assertTrue(ex.getMessage().contains("users.ibd"));
    }

    @Test
    void rejectsStampedPageSizeMismatch() {
        storage.create("shop/users/users.ibd");
        // File is valid for PAGE_SIZE 512, but stamp claims 256.
        byte[] meta = HeapMetaPage.createEmpty(0, PAGE_SIZE).toBytes();
        HeapMetaPage.wrap(meta).setPageSize(256);
        storage.write("shop/users/users.ibd", 0, meta);

        PhysicalStorageException ex = assertThrows(
                PhysicalStorageException.class,
                () -> PageFileValidator.validateAll(dataDirectory, storage)
        );
        assertTrue(ex.getMessage().contains("stamped PAGE_SIZE"));
    }

    @Test
    void rejectsWrongConfiguredPageSize() {
        storage.create("shop/users/users.ibd");
        storage.write("shop/users/users.ibd", 0, HeapMetaPage.createEmpty(0, PAGE_SIZE).toBytes());

        DefaultPhysicalStorage wrongSize = new DefaultPhysicalStorage(dataDirectory, 256);
        PhysicalStorageException ex = assertThrows(
                PhysicalStorageException.class,
                () -> PageFileValidator.validateAll(dataDirectory, wrongSize)
        );
        assertTrue(ex.getMessage().contains("PAGE_SIZE"));
    }
}
