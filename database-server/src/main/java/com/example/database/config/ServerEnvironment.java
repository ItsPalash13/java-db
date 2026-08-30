package com.example.database.config;

import com.example.database.storage.DataDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime settings stored under the data directory. On first start we write
 * {@value #ENV_FILE_NAME} with code defaults; operators can edit the file or
 * override any key with a same-named environment variable.
 */
public final class ServerEnvironment {

    public static final String ENV_FILE_NAME = "server.env";
    public static final String CATALOG_LOCK_WAIT_SECONDS = "CATALOG_LOCK_WAIT_SECONDS";
    public static final int DEFAULT_CATALOG_LOCK_WAIT_SECONDS = 30;

    private static final List<String> DEFAULT_FILE_LINES = List.of(
            "# Auto-generated server settings. Edit values or override with environment variables.",
            CATALOG_LOCK_WAIT_SECONDS + "=" + DEFAULT_CATALOG_LOCK_WAIT_SECONDS
    );

    private final Duration catalogLockWait;

    private ServerEnvironment(Duration catalogLockWait) {
        this.catalogLockWait = Objects.requireNonNull(catalogLockWait, "catalogLockWait");
    }

    /** Code defaults only — no file read (tests and in-memory engines). */
    public static ServerEnvironment defaults() {
        return new ServerEnvironment(Duration.ofSeconds(DEFAULT_CATALOG_LOCK_WAIT_SECONDS));
    }

    /**
     * Ensures {@code data/server.env} exists, loads file values, then applies
     * process environment overrides.
     */
    public static ServerEnvironment load(DataDirectory dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path envFile = dataDirectory.root().resolve(ENV_FILE_NAME);
        ensureDefaultsFile(envFile);
        Map<String, String> values = readFile(envFile);
        int lockWaitSeconds = resolveInt(
                CATALOG_LOCK_WAIT_SECONDS,
                values,
                DEFAULT_CATALOG_LOCK_WAIT_SECONDS
        );
        if (lockWaitSeconds < 1) {
            throw new IllegalArgumentException(
                    CATALOG_LOCK_WAIT_SECONDS + " must be at least 1 second, got " + lockWaitSeconds
            );
        }
        return new ServerEnvironment(Duration.ofSeconds(lockWaitSeconds));
    }

    public Duration catalogLockWait() {
        return catalogLockWait;
    }

    private static void ensureDefaultsFile(Path envFile) {
        if (Files.exists(envFile)) {
            return;
        }
        try {
            Files.createDirectories(envFile.getParent());
            Files.write(envFile, DEFAULT_FILE_LINES, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + envFile, e);
        }
    }

    private static Map<String, String> readFile(Path envFile) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                parseLine(line).ifPresent(entry -> values.put(entry.key(), entry.value()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + envFile, e);
        }
        return values;
    }

    private static java.util.Optional<Entry> parseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return java.util.Optional.empty();
        }
        int equals = trimmed.indexOf('=');
        if (equals <= 0) {
            return java.util.Optional.empty();
        }
        String key = trimmed.substring(0, equals).trim();
        String value = trimmed.substring(equals + 1).trim();
        if (key.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Entry(key, value));
    }

    private static int resolveInt(String key, Map<String, String> fileValues, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            raw = fileValues.get(key);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer for " + key + ": " + raw, e);
        }
    }

    private record Entry(String key, String value) {
    }
}
