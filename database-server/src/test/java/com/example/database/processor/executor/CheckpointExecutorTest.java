package com.example.database.processor.executor;

import com.example.database.processor.DefaultQueryProcessor;
import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.wal.DefaultWALManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointExecutorTest {

    @TempDir
    Path tempDir;

    private DefaultStorageEngine engine;
    private DefaultQueryProcessor processor;

    @BeforeEach
    void setUp() {
        engine = new DefaultStorageEngine(new DataDirectory(tempDir));
        engine.start();
        processor = new DefaultQueryProcessor(engine);
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void checkpointSqlAppendsMarkerAndKeepsPriorWalLines() throws Exception {
        assertFalse(processor.execute("CREATE DATABASE shop").isError());
        assertFalse(processor.execute(
                "CREATE TABLE shop.users (id INT)"
        ).isError());
        Path walPath = engine.dataDirectory().root().resolve(DefaultWALManager.WAL_FILE);
        String before = Files.readString(walPath, StandardCharsets.UTF_8);
        assertTrue(before.contains("CREATE_DATABASE"));

        QueryResult result = processor.execute("CHECKPOINT");
        assertFalse(result.isError());
        String after = Files.readString(walPath, StandardCharsets.UTF_8);
        assertTrue(after.contains("CREATE_DATABASE"));
        assertTrue(after.contains("CREATE_TABLE"));
        assertTrue(after.contains("\"op\":\"CHECKPOINT\""));
        assertTrue(engine.dataDirectory().root().resolve(DefaultWALManager.CHECKPOINT_FILE).toFile().isFile());
    }

    @Test
    void checkpointRejectedInsideExplicitTransaction() {
        assertFalse(processor.execute("BEGIN").isError());
        QueryResult result = processor.execute("CHECKPOINT");
        assertTrue(result.toResponse().contains("ERROR"));
        assertFalse(processor.execute("ROLLBACK").isError());
    }
}
