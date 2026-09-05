package com.example.database.config;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.bufferpool.DefaultBufferPool;
import com.example.database.storage.checkpoint.CheckpointStrategy;
import com.example.database.storage.checkpoint.CheckpointStrategyKind;
import com.example.database.storage.checkpoint.TimeoutCheckpointStrategy;
import com.example.database.storage.checkpoint.WalSizeCheckpointStrategy;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime settings stored under the data directory. On first start we write
 * {@value #ENV_FILE_NAME} with code defaults; operators can edit the file or
 * override any key with a same-named environment variable.
 * <p>
 * Checkpoint knobs select whether the background scheduler runs and which single
 * {@link com.example.database.storage.checkpoint.CheckpointStrategy} is plugged in.
 * Manual {@code CHECKPOINT} SQL ignores these flags and always goes through the executor.
 */
public final class ServerEnvironment {

    public static final String ENV_FILE_NAME = "server.env";
    public static final String CATALOG_LOCK_WAIT_SECONDS = "CATALOG_LOCK_WAIT_SECONDS";
    /** When false, {@code StorageEngine} still constructs the scheduler but never starts it. */
    public static final String CHECKPOINT_ENABLED = "CHECKPOINT_ENABLED";
    /** {@code timeout} or {@code wal_size} — exactly one automatic policy. */
    public static final String CHECKPOINT_STRATEGY = "CHECKPOINT_STRATEGY";
    public static final String CHECKPOINT_TIMEOUT_SECONDS = "CHECKPOINT_TIMEOUT_SECONDS";
    public static final String MAX_WAL_SIZE_BYTES = "MAX_WAL_SIZE_BYTES";
    /**
     * Fixed byte length of every {@code .ibd} / {@code .idx} page. Must match the size
     * used when those files were written — wrong values fail startup validation.
     */
    public static final String PAGE_SIZE = "PAGE_SIZE";
    /**
     * Trailing zero bytes after every on-disk index key (leaf + separator).
     * Does not change SQL key order — only fattens entries so B+ trees grow taller
     * with fewer rows (demos / page-graph). Default {@code 0}.
     * <p>
     * Side effect: more dirty index pages per insert under no-steal. Pair large values
     * with mid-load {@code CHECKPOINT} (see load_1k) rather than only growing the buffer pool.
     */
    public static final String INDEX_KEY_PADDING_BYTES = "INDEX_KEY_PADDING_BYTES";
    /**
     * Number of RAM page frames in {@link com.example.database.storage.bufferpool.DefaultBufferPool}.
     * Under no-steal, dirty pages occupy frames until {@code CHECKPOINT}/{@code flushAll}.
     * Raise this for fat-key demos if mid-load checkpoints are not enough; default matches
     * {@link com.example.database.storage.bufferpool.DefaultBufferPool#DEFAULT_FRAME_COUNT}.
     */
    public static final String BUFFER_POOL_FRAMES = "BUFFER_POOL_FRAMES";

    public static final int DEFAULT_CATALOG_LOCK_WAIT_SECONDS = 30;
    /** Five minutes — learning default; operators shorten for demos. */
    public static final int DEFAULT_CHECKPOINT_TIMEOUT_SECONDS = 300;
    public static final long DEFAULT_MAX_WAL_SIZE_BYTES = 16L * 1024 * 1024;
    public static final CheckpointStrategyKind DEFAULT_CHECKPOINT_STRATEGY = CheckpointStrategyKind.TIMEOUT;
    /** Same default as {@link DefaultPhysicalStorage#DEFAULT_PAGE_SIZE}. */
    public static final int DEFAULT_PAGE_SIZE = DefaultPhysicalStorage.DEFAULT_PAGE_SIZE;
    public static final int DEFAULT_INDEX_KEY_PADDING_BYTES = 0;
    /** Same default as {@link DefaultBufferPool#DEFAULT_FRAME_COUNT}. */
    public static final int DEFAULT_BUFFER_POOL_FRAMES = DefaultBufferPool.DEFAULT_FRAME_COUNT;

    private static final List<String> DEFAULT_FILE_LINES = List.of(
            "# Auto-generated server settings. Edit values or override with environment variables.",
            CATALOG_LOCK_WAIT_SECONDS + "=" + DEFAULT_CATALOG_LOCK_WAIT_SECONDS,
            CHECKPOINT_ENABLED + "=true",
            CHECKPOINT_STRATEGY + "=timeout",
            CHECKPOINT_TIMEOUT_SECONDS + "=" + DEFAULT_CHECKPOINT_TIMEOUT_SECONDS,
            MAX_WAL_SIZE_BYTES + "=" + DEFAULT_MAX_WAL_SIZE_BYTES,
            PAGE_SIZE + "=" + DEFAULT_PAGE_SIZE,
            INDEX_KEY_PADDING_BYTES + "=" + DEFAULT_INDEX_KEY_PADDING_BYTES,
            BUFFER_POOL_FRAMES + "=" + DEFAULT_BUFFER_POOL_FRAMES
    );

    private final Duration catalogLockWait;
    private final boolean checkpointEnabled;
    private final CheckpointStrategyKind checkpointStrategyKind;
    private final Duration checkpointTimeout;
    private final long maxWalSizeBytes;
    private final int pageSize;
    private final int indexKeyPaddingBytes;
    private final int bufferPoolFrames;

    private ServerEnvironment(
            Duration catalogLockWait,
            boolean checkpointEnabled,
            CheckpointStrategyKind checkpointStrategyKind,
            Duration checkpointTimeout,
            long maxWalSizeBytes,
            int pageSize,
            int indexKeyPaddingBytes,
            int bufferPoolFrames
    ) {
        this.catalogLockWait = Objects.requireNonNull(catalogLockWait, "catalogLockWait");
        this.checkpointStrategyKind = Objects.requireNonNull(checkpointStrategyKind, "checkpointStrategyKind");
        this.checkpointTimeout = Objects.requireNonNull(checkpointTimeout, "checkpointTimeout");
        this.checkpointEnabled = checkpointEnabled;
        this.maxWalSizeBytes = maxWalSizeBytes;
        this.pageSize = pageSize;
        this.indexKeyPaddingBytes = indexKeyPaddingBytes;
        this.bufferPoolFrames = bufferPoolFrames;
    }

    /**
     * Code defaults — no file read. Checkpoint scheduler off so unit tests do not
     * start a background thread unless they opt in via {@link #load}.
     */
    public static ServerEnvironment defaults() {
        return new ServerEnvironment(
                Duration.ofSeconds(DEFAULT_CATALOG_LOCK_WAIT_SECONDS),
                false,
                DEFAULT_CHECKPOINT_STRATEGY,
                Duration.ofSeconds(DEFAULT_CHECKPOINT_TIMEOUT_SECONDS),
                DEFAULT_MAX_WAL_SIZE_BYTES,
                DEFAULT_PAGE_SIZE,
                DEFAULT_INDEX_KEY_PADDING_BYTES,
                DEFAULT_BUFFER_POOL_FRAMES
        );
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
        boolean checkpointEnabled = resolveBoolean(CHECKPOINT_ENABLED, values, true);
        CheckpointStrategyKind strategyKind = resolveStrategy(values);
        int timeoutSeconds = resolveInt(
                CHECKPOINT_TIMEOUT_SECONDS,
                values,
                DEFAULT_CHECKPOINT_TIMEOUT_SECONDS
        );
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    CHECKPOINT_TIMEOUT_SECONDS + " must be at least 1 second, got " + timeoutSeconds
            );
        }
        long maxWalSize = resolveLong(MAX_WAL_SIZE_BYTES, values, DEFAULT_MAX_WAL_SIZE_BYTES);
        if (maxWalSize < 1) {
            throw new IllegalArgumentException(
                    MAX_WAL_SIZE_BYTES + " must be at least 1, got " + maxWalSize
            );
        }
        int pageSize = resolveInt(PAGE_SIZE, values, DEFAULT_PAGE_SIZE);
        requireValidPageSize(pageSize);
        int keyPadding = resolveInt(INDEX_KEY_PADDING_BYTES, values, DEFAULT_INDEX_KEY_PADDING_BYTES);
        requireValidKeyPadding(keyPadding);
        int poolFrames = resolveInt(BUFFER_POOL_FRAMES, values, DEFAULT_BUFFER_POOL_FRAMES);
        requireValidBufferPoolFrames(poolFrames);
        return new ServerEnvironment(
                Duration.ofSeconds(lockWaitSeconds),
                checkpointEnabled,
                strategyKind,
                Duration.ofSeconds(timeoutSeconds),
                maxWalSize,
                pageSize,
                keyPadding,
                poolFrames
        );
    }

    public Duration catalogLockWait() {
        return catalogLockWait;
    }

    public boolean checkpointEnabled() {
        return checkpointEnabled;
    }

    public CheckpointStrategyKind checkpointStrategyKind() {
        return checkpointStrategyKind;
    }

    public Duration checkpointTimeout() {
        return checkpointTimeout;
    }

    public long maxWalSizeBytes() {
        return maxWalSizeBytes;
    }

    /**
     * Byte length of each heap/index page ({@code .ibd} / {@code .idx}).
     * Wired into {@link DefaultPhysicalStorage}; changing it on a non-empty data
     * directory requires matching on-disk pages or startup validation fails.
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * Trailing zeros after each encoded index key. Wired into {@link FileIndexStore}
     * / {@link IndexKeyCodec}; stamped on {@code .idx} meta. {@code 0} means no pad.
     */
    public int indexKeyPaddingBytes() {
        return indexKeyPaddingBytes;
    }

    /**
     * Frame count for {@link com.example.database.storage.bufferpool.DefaultBufferPool}.
     * Not stamped on disk — safe to change between restarts (unlike {@link #pageSize()}).
     */
    public int bufferPoolFrames() {
        return bufferPoolFrames;
    }

    /**
     * Plug exactly one automatic strategy for the scheduler.
     * Change {@link #CHECKPOINT_STRATEGY} in {@code server.env} to switch implementations —
     * do not OR timeout and size in one class (that would abandon the Strategy split).
     */
    public CheckpointStrategy createCheckpointStrategy(PhysicalStorage physicalStorage) {
        Objects.requireNonNull(physicalStorage, "physicalStorage");
        return switch (checkpointStrategyKind) {
            case TIMEOUT -> new TimeoutCheckpointStrategy(checkpointTimeout);
            case WAL_SIZE -> new WalSizeCheckpointStrategy(physicalStorage, maxWalSizeBytes);
        };
    }

    /**
     * lower/upper are u16 offsets, so page size must fit in 16 bits and leave room
     * for the header plus at least one slot directory entry.
     */
    static void requireValidPageSize(int pageSize) {
        if (pageSize < PageLayout.MIN_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    PAGE_SIZE + " must be at least " + PageLayout.MIN_PAGE_SIZE
                            + " (header + meta fields), got " + pageSize
            );
        }
        if (pageSize > 0xFFFF) {
            throw new IllegalArgumentException(
                    PAGE_SIZE + " must fit in u16 page offsets, got " + pageSize
            );
        }
    }

    static void requireValidKeyPadding(int paddingBytes) {
        if (paddingBytes < 0 || paddingBytes > 0x8000) {
            throw new IllegalArgumentException(
                    INDEX_KEY_PADDING_BYTES + " must be in [0, 32768], got " + paddingBytes
            );
        }
    }

    static void requireValidBufferPoolFrames(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException(
                    BUFFER_POOL_FRAMES + " must be at least 1, got " + frames
            );
        }
        // Soft cap: each frame holds one PAGE_SIZE buffer; tens of thousands is still demo-sized.
        if (frames > 100_000) {
            throw new IllegalArgumentException(
                    BUFFER_POOL_FRAMES + " too large (max 100000), got " + frames
            );
        }
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

    private static CheckpointStrategyKind resolveStrategy(Map<String, String> fileValues) {
        String raw = System.getenv(CHECKPOINT_STRATEGY);
        if (raw == null || raw.isBlank()) {
            raw = fileValues.get(CHECKPOINT_STRATEGY);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CHECKPOINT_STRATEGY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "timeout" -> CheckpointStrategyKind.TIMEOUT;
            case "wal_size", "walsize" -> CheckpointStrategyKind.WAL_SIZE;
            default -> throw new IllegalArgumentException(
                    CHECKPOINT_STRATEGY + " must be timeout or wal_size, got: " + raw
            );
        };
    }

    private static boolean resolveBoolean(String key, Map<String, String> fileValues, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            raw = fileValues.get(key);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("invalid boolean for " + key + ": " + raw);
        };
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

    private static long resolveLong(String key, Map<String, String> fileValues, long defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            raw = fileValues.get(key);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid long for " + key + ": " + raw, e);
        }
    }

    private record Entry(String key, String value) {
    }
}
