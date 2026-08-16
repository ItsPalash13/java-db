package com.example.database.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * On-disk store root chosen at launch. {@link #ensureExists()} creates only this folder.
 * <p>
 * Intended layout (databases and tables are created later, not here):
 * <pre>
 *   &lt;root&gt;/                      // main data folder (default: ./data)
 *     &lt;database&gt;/                // one subfolder per database
 *       &lt;table&gt;/                 // one subfolder per table
 *         data/                  // row storage
 *         metadata/              // schema / index metadata
 * </pre>
 */
public final class DataDirectory {

    public static final String DEFAULT_FOLDER_NAME = "data";

    private final Path root;

    public DataDirectory(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** {@code ./data} relative to the process working directory. */
    public static DataDirectory defaults() {
        return new DataDirectory(Path.of(DEFAULT_FOLDER_NAME));
    }

    public Path root() {
        return root;
    }

    /**
     * Creates the store root if it is missing. Does not create database or table folders.
     *
     * @throws UncheckedIOException if {@code root} exists and is not a directory, or cannot be created
     */
    public void ensureExists() {
        try {
            if (Files.exists(root) && !Files.isDirectory(root)) {
                throw new IOException("data directory is not a directory: " + root);
            }
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create data directory: " + root, e);
        }
    }
}
