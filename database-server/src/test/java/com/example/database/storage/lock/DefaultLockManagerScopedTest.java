package com.example.database.storage.lock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLockManagerScopedTest {

    @Test
    void twoReadersOnSameTableBothEnter() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofSeconds(2));
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            long owner = i + 1L;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                locks.bindOwner(owner);
                try {
                    locks.runWithTable("shop", "users", LockMode.S, () -> {
                        int now = concurrent.incrementAndGet();
                        maxSeen.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        concurrent.decrementAndGet();
                        return null;
                    });
                } finally {
                    locks.clearOwnerBinding();
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(2, maxSeen.get());
    }

    @Test
    void writerBlocksReaderOnSameTable() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofMillis(200));
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicInteger readerEntered = new AtomicInteger();

        Thread writer = new Thread(() -> {
            locks.bindOwner(1L);
            try {
                locks.runWithTable("shop", "users", LockMode.X, () -> {
                    writerStarted.countDown();
                    try {
                        releaseWriter.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } finally {
                locks.clearOwnerBinding();
            }
        });
        writer.start();
        writerStarted.await();

        Thread reader = new Thread(() -> {
            locks.bindOwner(2L);
            try {
                try {
                    locks.runWithTable("shop", "users", LockMode.S, () -> {
                        readerEntered.incrementAndGet();
                        return null;
                    });
                } finally {
                    locks.clearOwnerBinding();
                }
            } catch (CatalogLockException ignored) {
                // timed out waiting for writer
            }
        });
        reader.start();
        Thread.sleep(100);
        assertEquals(0, readerEntered.get());
        releaseWriter.countDown();
        writer.join();
        reader.join();
        assertEquals(1, readerEntered.get());
    }

    @Test
    void differentTablesCanRunConcurrently() throws Exception {
        DefaultLockManager locks = new DefaultLockManager(Duration.ofSeconds(2));
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Runnable users = () -> locks.runWithTable("shop", "users", LockMode.X, () -> {
            int now = concurrent.incrementAndGet();
            maxSeen.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return null;
        });
        Runnable orders = () -> locks.runWithTable("shop", "orders", LockMode.X, () -> {
            int now = concurrent.incrementAndGet();
            maxSeen.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return null;
        });

        Thread t1 = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            locks.bindOwner(1L);
            try {
                users.run();
            } finally {
                locks.clearOwnerBinding();
            }
        });
        Thread t2 = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            locks.bindOwner(2L);
            try {
                orders.run();
            } finally {
                locks.clearOwnerBinding();
            }
        });
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();
        assertEquals(2, maxSeen.get());
    }
}
