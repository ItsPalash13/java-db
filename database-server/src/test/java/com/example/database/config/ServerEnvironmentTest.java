package com.example.database.config;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.checkpoint.CheckpointStrategyKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEnvironmentTest {

    @TempDir
    Path tempDir;

    @Test
    void loadCreatesServerEnvWithDefaults() throws Exception {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("data"));
        dataDirectory.ensureExists();

        ServerEnvironment environment = ServerEnvironment.load(dataDirectory);

        assertEquals(Duration.ofSeconds(30), environment.catalogLockWait());
        assertTrue(environment.checkpointEnabled());
        assertEquals(CheckpointStrategyKind.TIMEOUT, environment.checkpointStrategyKind());
        assertEquals(Duration.ofSeconds(300), environment.checkpointTimeout());
        assertEquals(16L * 1024 * 1024, environment.maxWalSizeBytes());
        assertEquals(ServerEnvironment.DEFAULT_PAGE_SIZE, environment.pageSize());
        assertEquals(ServerEnvironment.DEFAULT_INDEX_KEY_PADDING_BYTES, environment.indexKeyPaddingBytes());
        assertEquals(ServerEnvironment.DEFAULT_BUFFER_POOL_FRAMES, environment.bufferPoolFrames());
        Path envFile = dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME);
        assertTrue(Files.isRegularFile(envFile));
        String contents = Files.readString(envFile);
        assertTrue(contents.contains("CATALOG_LOCK_WAIT_SECONDS=30"));
        assertTrue(contents.contains("CHECKPOINT_STRATEGY=timeout"));
        assertTrue(contents.contains("PAGE_SIZE=16384"));
        assertTrue(contents.contains("INDEX_KEY_PADDING_BYTES=0"));
        assertTrue(contents.contains("BUFFER_POOL_FRAMES=128"));
    }

    @Test
    void loadReadsValueFromExistingFile() throws Exception {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("data"));
        dataDirectory.ensureExists();
        Files.writeString(
                dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME),
                """
                        CATALOG_LOCK_WAIT_SECONDS=12
                        CHECKPOINT_ENABLED=false
                        CHECKPOINT_STRATEGY=wal_size
                        MAX_WAL_SIZE_BYTES=1024
                        PAGE_SIZE=4096
                        INDEX_KEY_PADDING_BYTES=128
                        BUFFER_POOL_FRAMES=64
                        """
        );

        ServerEnvironment environment = ServerEnvironment.load(dataDirectory);

        assertEquals(Duration.ofSeconds(12), environment.catalogLockWait());
        assertFalse(environment.checkpointEnabled());
        assertEquals(CheckpointStrategyKind.WAL_SIZE, environment.checkpointStrategyKind());
        assertEquals(1024L, environment.maxWalSizeBytes());
        assertEquals(4096, environment.pageSize());
        assertEquals(128, environment.indexKeyPaddingBytes());
        assertEquals(64, environment.bufferPoolFrames());
    }

    @Test
    void loadRejectsPageSizeTooSmall() throws Exception {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("data-small-page"));
        dataDirectory.ensureExists();
        Files.writeString(
                dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME),
                "PAGE_SIZE=8\n"
        );

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ServerEnvironment.load(dataDirectory)
        );
        assertTrue(thrown.getMessage().contains("PAGE_SIZE"));
    }

    @Test
    void loadRejectsBufferPoolFramesBelowOne() throws Exception {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("data-pool-zero"));
        dataDirectory.ensureExists();
        Files.writeString(
                dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME),
                "BUFFER_POOL_FRAMES=0\n"
        );

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ServerEnvironment.load(dataDirectory)
        );
        assertTrue(thrown.getMessage().contains("BUFFER_POOL_FRAMES"));
    }

    @Test
    void defaultsSkipsFile() {
        ServerEnvironment environment = ServerEnvironment.defaults();
        assertEquals(Duration.ofSeconds(30), environment.catalogLockWait());
        assertFalse(environment.checkpointEnabled());
        assertEquals(CheckpointStrategyKind.TIMEOUT, environment.checkpointStrategyKind());
        assertEquals(ServerEnvironment.DEFAULT_PAGE_SIZE, environment.pageSize());
        assertEquals(0, environment.indexKeyPaddingBytes());
        assertEquals(ServerEnvironment.DEFAULT_BUFFER_POOL_FRAMES, environment.bufferPoolFrames());
    }
}
