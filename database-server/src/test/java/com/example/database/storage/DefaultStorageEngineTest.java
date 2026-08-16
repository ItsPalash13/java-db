package com.example.database.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStorageEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void startCreatesDataDirectory() {
        Path root = tempDir.resolve("store");
        DefaultStorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            assertTrue(Files.isDirectory(engine.dataDirectory().root()));
        } finally {
            engine.stop();
        }
    }
}
