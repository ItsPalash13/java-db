package com.example.database.config;

import com.example.database.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Path envFile = dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME);
        assertTrue(Files.isRegularFile(envFile));
        String contents = Files.readString(envFile);
        assertTrue(contents.contains("CATALOG_LOCK_WAIT_SECONDS=30"));
    }

    @Test
    void loadReadsValueFromExistingFile() throws Exception {
        DataDirectory dataDirectory = new DataDirectory(tempDir.resolve("data"));
        dataDirectory.ensureExists();
        Files.writeString(
                dataDirectory.root().resolve(ServerEnvironment.ENV_FILE_NAME),
                "CATALOG_LOCK_WAIT_SECONDS=12\n"
        );

        ServerEnvironment environment = ServerEnvironment.load(dataDirectory);

        assertEquals(Duration.ofSeconds(12), environment.catalogLockWait());
    }

    @Test
    void defaultsSkipsFile() {
        ServerEnvironment environment = ServerEnvironment.defaults();
        assertEquals(Duration.ofSeconds(30), environment.catalogLockWait());
    }
}
