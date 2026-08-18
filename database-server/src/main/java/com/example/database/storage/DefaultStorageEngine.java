package com.example.database.storage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default storage stub: owns {@link DataDirectory} and creates the store root on {@link #start()}.
 * Submodules (catalog, table store, …) are not wired yet.
 */
public final class DefaultStorageEngine implements StorageEngine {

    private final DataDirectory dataDirectory;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DefaultStorageEngine(DataDirectory dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Override
    public DataDirectory dataDirectory() {
        return dataDirectory;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dataDirectory.ensureExists();
        System.out.println("[StorageEngine] data directory: " + dataDirectory.root());
        System.out.println("[StorageEngine] started");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        System.out.println("[StorageEngine] stopped");
    }
}
