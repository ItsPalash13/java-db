package com.example.database.storage.transaction;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.lock.CatalogLockException;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.physical.DefaultPhysicalStorage;
import com.example.database.storage.table.InMemoryTableStore;
import com.example.database.storage.wal.DefaultWALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitTransactionLockTest {

    @TempDir
    Path tempDir;

    @Test
    void twoConnectionsCanBeginConcurrently() throws Exception {
        LockManager lock = new DefaultLockManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        InMemoryTableStore tableStore = new InMemoryTableStore();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicReference<Exception> firstError = new AtomicReference<>();
        AtomicReference<Exception> secondError = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                transactions.beginExplicit(lock, catalog, tableStore);
                bothStarted.countDown();
                transactions.rollbackExplicit(lock, catalog, tableStore);
            } catch (RuntimeException e) {
                firstError.set(e);
                bothStarted.countDown();
            }
        });
        Thread second = new Thread(() -> {
            try {
                transactions.beginExplicit(lock, catalog, tableStore);
                bothStarted.countDown();
                transactions.rollbackExplicit(lock, catalog, tableStore);
            } catch (RuntimeException e) {
                secondError.set(e);
                bothStarted.countDown();
            }
        });

        first.start();
        second.start();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));

        first.join(5_000);
        second.join(5_000);

        assertNull(firstError.get());
        assertNull(secondError.get());
        assertEquals(0, transactions.activeExplicitSessionCount());
    }

    @Test
    void commitWaitsForCatalogLockHeldByAnotherConnection() throws Exception {
        LockManager lock = new DefaultLockManager(Duration.ofSeconds(2));
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        InMemoryTableStore tableStore = new InMemoryTableStore();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        CountDownLatch beginDone = new CountDownLatch(1);
        AtomicReference<Exception> sessionError = new AtomicReference<>();

        Thread session = new Thread(() -> {
            try {
                transactions.beginExplicit(lock, catalog, tableStore);
                beginDone.countDown();
                transactions.commitExplicit(lock, catalog, tableStore);
            } catch (RuntimeException e) {
                sessionError.set(e);
                beginDone.countDown();
            }
        });

        Thread blocker = new Thread(() -> {
            try {
                beginDone.await(5, TimeUnit.SECONDS);
                lock.lockExclusiveCatalog();
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlockExclusiveCatalog();
            }
        });

        session.start();
        blocker.start();
        session.join(5_000);
        blocker.join(5_000);

        assertNull(sessionError.get());
        assertEquals(0, transactions.activeExplicitSessionCount());
    }

    @Test
    void commitTimesOutWhenCatalogLockHeldPastWaitLimit() throws Exception {
        LockManager lock = new DefaultLockManager(Duration.ofMillis(200));
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        InMemoryTableStore tableStore = new InMemoryTableStore();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        CountDownLatch catalogHeld = new CountDownLatch(1);
        AtomicReference<Exception> sessionError = new AtomicReference<>();

        Thread blocker = new Thread(() -> {
            lock.lockExclusiveCatalog();
            catalogHeld.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlockExclusiveCatalog();
            }
        });

        Thread session = new Thread(() -> {
            try {
                catalogHeld.await(5, TimeUnit.SECONDS);
                transactions.beginExplicit(lock, catalog, tableStore);
                transactions.commitExplicit(lock, catalog, tableStore);
            } catch (Exception e) {
                sessionError.set(e);
            }
        });

        blocker.start();
        catalogHeld.await(5, TimeUnit.SECONDS);
        session.start();
        session.join(5_000);
        blocker.join(5_000);

        Exception error = sessionError.get();
        assertTrue(error instanceof CatalogLockException);
        assertTrue(error.getMessage().contains("timed out"));
        assertEquals(0, transactions.activeExplicitSessionCount());
    }

    @Test
    void endConnectionSessionAllowsNewBegin() {
        LockManager lock = new DefaultLockManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        InMemoryTableStore tableStore = new InMemoryTableStore();
        catalog.createDatabase("shop");
        TransactionManager transactions = newTransactionManager();

        transactions.beginExplicit(lock, catalog, tableStore);
        transactions.endConnectionSession(lock, catalog, tableStore);

        assertNull(secondBeginError(transactions, lock, catalog, tableStore));
        assertEquals(0, transactions.activeExplicitSessionCount());
    }

    private TransactionManager newTransactionManager() {
        return new DefaultTransactionManager(
                new DefaultWALManager(new DefaultPhysicalStorage(new DataDirectory(tempDir)))
        );
    }

    private static Exception secondBeginError(
            TransactionManager transactions,
            LockManager lock,
            DefaultCatalogManager catalog,
            InMemoryTableStore tableStore
    ) {
        try {
            transactions.beginExplicit(lock, catalog, tableStore);
            transactions.rollbackExplicit(lock, catalog, tableStore);
            return null;
        } catch (Exception e) {
            return e;
        }
    }
}
