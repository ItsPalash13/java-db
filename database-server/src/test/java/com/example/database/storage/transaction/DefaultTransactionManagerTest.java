package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogManager;
import com.example.database.storage.undo.DefaultUndoManager;
import com.example.database.storage.wal.WALManager;
import com.example.database.storage.wal.WalRecord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTransactionManagerTest {

    @Test
    void runInTransactionReturnsSupplierResultOnSuccess() {
        FakeWal wal = new FakeWal();
        TransactionManager tx = new DefaultTransactionManager(wal, new DefaultUndoManager());

        String result = tx.runInTransaction(() -> "ok");

        assertEquals("ok", result);
        assertTrue(wal.flushCount >= 1);
    }

    @Test
    void runInTransactionPropagatesFailureAndDiscardsPending() {
        FakeWal wal = new FakeWal();
        TransactionManager tx = new DefaultTransactionManager(wal, new DefaultUndoManager());
        AtomicInteger calls = new AtomicInteger();

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> tx.runInTransaction(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("boom");
                })
        );

        assertEquals("boom", ex.getMessage());
        assertEquals(1, calls.get());
        assertTrue(wal.discardCount >= 1);
    }

    @Test
    void rejectsNestedTransactions() {
        TransactionManager tx = new DefaultTransactionManager(new FakeWal(), new DefaultUndoManager());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tx.runInTransaction(() ->
                        tx.runInTransaction(() -> "nested")
                )
        );

        assertTrue(ex.getMessage().contains("nested"));
    }

    @Test
    void sequentialTransactionsAreAllowedOnSameThread() {
        TransactionManager tx = new DefaultTransactionManager(new FakeWal(), new DefaultUndoManager());

        assertEquals(1, tx.runInTransaction(() -> 1));
        assertEquals(2, tx.runInTransaction(() -> 2));
    }

    private static final class FakeWal implements WALManager {
        int flushCount;
        int discardCount;

        @Override
        public void append(WalRecord record) {
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void discardPending() {
            discardCount++;
        }

        @Override
        public int replay(CatalogManager catalogManager) {
            return 0;
        }

        @Override
        public int checkpoint() {
            return 0;
        }
    }
}
