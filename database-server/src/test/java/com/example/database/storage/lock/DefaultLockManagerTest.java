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
}
