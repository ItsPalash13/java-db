package com.example.database.storage.lock;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One catalog exclusive lock for the whole engine. Blocks until free; unlocks in
 * {@code finally} so exceptions still release. Table/row scopes are not here yet.
 */
public final class DefaultLockManager implements LockManager {

    // One lock for all catalog DDL in this process — shared by every connection thread.
    private final ReentrantLock catalogLock = new ReentrantLock();

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
        catalogLock.lock();
        try {
            return action.get();
        } finally {
            catalogLock.unlock();
        }
    }

    @Override
    public void lockExclusiveCatalog() {
        catalogLock.lock();
    }

    @Override
    public boolean tryLockExclusiveCatalog() {
        return catalogLock.tryLock();
    }

    @Override
    public void unlockExclusiveCatalog() {
        catalogLock.unlock();
    }
}
