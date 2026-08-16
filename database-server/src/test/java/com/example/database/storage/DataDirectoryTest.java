package com.example.database.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsUsesDataFolderName() {
        assertEquals("data", DataDirectory.defaults().root().getFileName().toString());
    }

    @Test
    void ensureExistsCreatesMissingDirectory() {
        Path root = tempDir.resolve("store");
        DataDirectory dataDirectory = new DataDirectory(root);
        dataDirectory.ensureExists();
        assertTrue(Files.isDirectory(dataDirectory.root()));
        assertEquals(root.toAbsolutePath().normalize(), dataDirectory.root());
    }

    @Test
    void ensureExistsIsIdempotentWhenDirectoryAlreadyExists() throws IOException {
        Path root = tempDir.resolve("store");
        Files.createDirectories(root);
        DataDirectory dataDirectory = new DataDirectory(root);
        dataDirectory.ensureExists();
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void ensureExistsRejectsExistingFile() throws IOException {
        Path file = tempDir.resolve("not-a-dir");
        Files.writeString(file, "x");
        DataDirectory dataDirectory = new DataDirectory(file);
        assertThrows(UncheckedIOException.class, dataDirectory::ensureExists);
    }
}
