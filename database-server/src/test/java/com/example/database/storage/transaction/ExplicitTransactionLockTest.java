package com.example.database.storage.transaction;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.wal.DefaultWALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitTransactionLockTest {

    @TempDir
    Path tempDir;

    @Test
    void beginFailsFastWhenAnotherConnectionHoldsCatalogLock() throws Exception {
        LockManager lock = new DefaultLockManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicReference<Exception> secondBeginError = new AtomicReference<>();

        Thread first = new Thread(() -> {
            transactions.beginExplicit(lock, catalog);
            firstStarted.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (transactions.inExplicitTransaction()) {
                    transactions.rollbackExplicit(lock, catalog);
                }
            }
        });
        Thread second = new Thread(() -> {
            try {
                firstStarted.await(5, TimeUnit.SECONDS);
                transactions.beginExplicit(lock, catalog);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                secondBeginError.set(e);
            }
        });

        first.start();
        firstStarted.await(5, TimeUnit.SECONDS);
        second.start();
        second.join(7_000);

        Exception error = secondBeginError.get();
        assertTrue(error instanceof IllegalStateException);
        assertTrue(error.getMessage().contains("catalog is locked"));

        first.interrupt();
        first.join(2_000);
    }

    @Test
    void endConnectionSessionReleasesCatalogLock() {
        LockManager lock = new DefaultLockManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        transactions.beginExplicit(lock, catalog);
        transactions.endConnectionSession(lock, catalog);

        assertNull(secondBeginError(transactions, lock, catalog));
    }

    private TransactionManager newTransactionManager() {
        return new DefaultTransactionManager(
                new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)))
        );
    }

    private static Exception secondBeginError(
            TransactionManager transactions,
            LockManager lock,
            DefaultCatalogManager catalog
    ) {
        try {
            transactions.beginExplicit(lock, catalog);
            transactions.rollbackExplicit(lock, catalog);
            return null;
        } catch (Exception e) {
            return e;
        }
    }
}
