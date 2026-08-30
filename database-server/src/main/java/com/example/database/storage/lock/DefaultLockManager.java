package com.example.database.storage.lock;

import com.example.database.config.ServerEnvironment;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One catalog exclusive lock for the whole engine. Waits up to {@link #catalogLockWait}
 * like {@code lock_timeout}; unlocks in {@code finally} so exceptions still release.
 */
public final class DefaultLockManager implements LockManager {

    static final Duration DEFAULT_CATALOG_LOCK_WAIT =
            Duration.ofSeconds(ServerEnvironment.DEFAULT_CATALOG_LOCK_WAIT_SECONDS);

    // One lock for all catalog DDL in this process — shared by every connection thread.
    private final ReentrantLock catalogLock = new ReentrantLock();
    private final Duration catalogLockWait;

    public DefaultLockManager() {
        this(DEFAULT_CATALOG_LOCK_WAIT);
    }

    public DefaultLockManager(Duration catalogLockWait) {
        this.catalogLockWait = Objects.requireNonNull(catalogLockWait, "catalogLockWait");
        if (catalogLockWait.isNegative() || catalogLockWait.isZero()) {
            throw new IllegalArgumentException("catalogLockWait must be positive");
        }
    }

    Duration catalogLockWait() {
        return catalogLockWait;
    }

    @Override
    public void runExclusiveCatalog(Runnable action) {
        Objects.requireNonNull(action, "action");
        runExclusiveCatalog(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T runExclusiveCatalog(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        acquireCatalogLock();
        try {
            return action.get();
        } finally {
            catalogLock.unlock();
        }
    }

    @Override
    public void lockExclusiveCatalog() {
        acquireCatalogLock();
    }

    @Override
    public void unlockExclusiveCatalog() {
        catalogLock.unlock();
    }

    private void acquireCatalogLock() {
        try {
            if (!catalogLock.tryLock(catalogLockWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new CatalogLockException(
                        "catalog lock wait timed out after " + catalogLockWait.toSeconds() + "s"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogLockException("catalog lock wait interrupted", e);
        }
    }
}
