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
        Path envFile = dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME);
        assertTrue(Files.isRegularFile(envFile));
        String contents = Files.readString(envFile);
        assertTrue(contents.contains("CATALOG_LOCK_WAIT_SECONDS=30"));
        assertTrue(contents.contains("CHECKPOINT_STRATEGY=timeout"));
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
                        """
        );

        ServerEnvironment environment = ServerEnvironment.load(dataDirectory);

        assertEquals(Duration.ofSeconds(12), environment.catalogLockWait());
        assertFalse(environment.checkpointEnabled());
        assertEquals(CheckpointStrategyKind.WAL_SIZE, environment.checkpointStrategyKind());
        assertEquals(1024L, environment.maxWalSizeBytes());
    }

    @Test
    void defaultsSkipsFile() {
        ServerEnvironment environment = ServerEnvironment.defaults();
        assertEquals(Duration.ofSeconds(30), environment.catalogLockWait());
        assertFalse(environment.checkpointEnabled());
        assertEquals(CheckpointStrategyKind.TIMEOUT, environment.checkpointStrategyKind());
    }
}
