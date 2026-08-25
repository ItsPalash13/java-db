package com.example.database.storage;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.physical.PhysicalStorage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the data directory, physical files, and catalog manager.
 * Catalog JSON lives inside {@link CatalogManager}, not here.
 */
public final class DefaultStorageEngine implements StorageEngine {

    private final DataDirectory dataDirectory;
    private final PhysicalStorage physicalStorage;
    private final DefaultCatalogManager catalogManager;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DefaultStorageEngine(DataDirectory dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.physicalStorage = new DefaultPhysicalStorage(dataDirectory);
        this.catalogManager = new DefaultCatalogManager(physicalStorage);
    }

    @Override
    public DataDirectory dataDirectory() {
        return dataDirectory;
    }

    @Override
    public CatalogManager catalogManager() {
        if (!running.get()) {
            throw new IllegalStateException("storage is not started");
        }
        return catalogManager;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dataDirectory.ensureExists();
        catalogManager.load();
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
