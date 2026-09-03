package com.example.database.storage.lock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLockManagerTest {

    @Test
    void runExclusiveCatalogReturnsSupplierResult() {
        LockManager locks = new DefaultLockManager();

        assertEquals("ok", locks.runExclusiveCatalog(() -> "ok"));
    }

    @Test
    void catalogLockSerializesConcurrentWriters() throws Exception {
        LockManager locks = new DefaultLockManager();
        AtomicInteger inCritical = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                locks.runExclusiveCatalog(() -> {
                    int current = inCritical.incrementAndGet();
                    maxSeen.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inCritical.decrementAndGet();
                    return null;
                });
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, maxSeen.get(), "only one thread should hold the catalog lock at a time");
    }

    @Test
    void unlocksEvenWhenActionThrows() {
        LockManager locks = new DefaultLockManager();

        try {
            locks.runExclusiveCatalog(() -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException ignored) {
            // expected
        }

        // If unlock failed, this would block forever.
        assertTrue(locks.runExclusiveCatalog(() -> true));
    }

    @Test
    void runExclusiveCatalogTimesOutWhenLockIsHeld() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofMillis(200));
        CountDownLatch holderReady = new CountDownLatch(1);
        Thread holder = new Thread(() -> locks.runExclusiveCatalog(() -> {
            holderReady.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        holderReady.await(5, TimeUnit.SECONDS);

        CatalogLockException error = assertThrows(
                CatalogLockException.class,
                () -> locks.runExclusiveCatalog(() -> true)
        );
        assertTrue(error.getMessage().contains("timed out"));

        holder.join(3_000);
    }

    @Test
    void engineIsCompatibleWithIsAndIx() {
        DefaultLockManager locks = new DefaultLockManager();
        locks.bindOwner(1L);
        locks.lockEngine(LockMode.IS);
        locks.bindOwner(2L);
        locks.lockEngine(LockMode.IS);
        locks.lockEngine(LockMode.IX);
        locks.unlockEngine(LockMode.IX);
        locks.unlockEngine(LockMode.IS);
        locks.clearOwnerBinding();
        locks.bindOwner(1L);
        locks.unlockEngine(LockMode.IS);
        locks.clearOwnerBinding();
    }

    @Test
    void engineIxCompatibleWithIx() {
        DefaultLockManager locks = new DefaultLockManager();
        locks.bindOwner(1L);
        locks.lockEngine(LockMode.IX);
        locks.bindOwner(2L);
        locks.lockEngine(LockMode.IX);
        locks.unlockEngine(LockMode.IX);
        locks.clearOwnerBinding();
        locks.bindOwner(1L);
        locks.unlockEngine(LockMode.IX);
        locks.clearOwnerBinding();
    }

    @Test
    void engineXConflictsWithIsAndIx() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofMillis(300));
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            locks.bindOwner(1L);
            try {
                locks.lockEngine(LockMode.IS);
                holderReady.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlockEngine(LockMode.IS);
                locks.clearOwnerBinding();
            }
        });
        holder.start();
        holderReady.await(5, TimeUnit.SECONDS);

        assertThrows(LockException.class, () -> locks.runWithEngineX(() -> null));
        releaseHolder.countDown();
        holder.join(3_000);

        locks.bindOwner(2L);
        locks.lockEngine(LockMode.IX);
        Thread waiter = new Thread(() -> {
            try {
                locks.runWithEngineX(() -> null);
            } catch (LockException ignored) {
                // timeout expected while IX held
            }
        });
        waiter.start();
        waiter.join(2_000);
        locks.unlockEngine(LockMode.IX);
        locks.clearOwnerBinding();
    }

    @Test
    void unlockSharedDropsEngineIsNotEngineIx() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofMillis(300));
        locks.bindOwner(1L);
        locks.lockEngine(LockMode.IS);
        locks.lockEngine(LockMode.IX);
        locks.unlockSharedForOwner();

        CountDownLatch xBlocked = new CountDownLatch(1);
        AtomicInteger xEntered = new AtomicInteger();
        Thread xWaiter = new Thread(() -> {
            locks.bindOwner(2L);
            try {
                xBlocked.countDown();
                locks.lockEngine(LockMode.X);
                xEntered.incrementAndGet();
                locks.unlockEngine(LockMode.X);
            } finally {
                locks.clearOwnerBinding();
            }
        });
        xWaiter.start();
        assertTrue(xBlocked.await(2, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(0, xEntered.get(), "ENGINE IX must survive unlockSharedForOwner");
        locks.unlockEngine(LockMode.IX);
        locks.clearOwnerBinding();
        xWaiter.join(3_000);
        assertEquals(1, xEntered.get());
    }
}
