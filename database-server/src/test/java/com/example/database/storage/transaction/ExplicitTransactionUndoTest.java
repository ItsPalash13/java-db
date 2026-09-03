package com.example.database.storage.transaction;

import com.example.database.storage.catalog.DefaultCatalogManager;
import com.example.database.storage.lock.DefaultLockManager;
import com.example.database.storage.lock.LockManager;
import com.example.database.storage.table.InMemoryTableStore;
import com.example.database.storage.table.UndoableTableStore;
import com.example.database.storage.undo.DefaultUndoManager;
import com.example.database.storage.undo.UndoManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitTransactionUndoTest {

    @Test
    void rollbackExplicitRestoresUpdatedRow() {
        UndoManager undo = new DefaultUndoManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        LockManager lock = new DefaultLockManager();
        InMemoryTableStore heap = new InMemoryTableStore();
        DefaultTransactionManager tx = new DefaultTransactionManager(new FakeWal(), undo);
        UndoableTableStore store = new UndoableTableStore(heap, undo, tx);

        store.insert("shop", "users", new Object[] {2, "two"});

        tx.beginExplicit(lock, catalog, store);
        store.update("shop", "users", 1L, new Object[] {2, "t2-r2"});
        tx.rollbackExplicit(lock, catalog, store);

        assertEquals("two", heap.findByRowId("shop", "users", 1L).orElseThrow().values()[1]);
    }

    @Test
    void lockAbortViaProcessorPathRestoresRow() {
        UndoManager undo = new DefaultUndoManager();
        DefaultCatalogManager catalog = new DefaultCatalogManager();
        catalog.createDatabase("shop");
        LockManager lock = new DefaultLockManager();
        InMemoryTableStore heap = new InMemoryTableStore();
        DefaultTransactionManager tx = new DefaultTransactionManager(new FakeWal(), undo);
        UndoableTableStore store = new UndoableTableStore(heap, undo, tx);

        store.insert("shop", "users", new Object[] {2, "two"});

        tx.beginExplicit(lock, catalog, store);
        assertTrue(tx.inExplicitTransaction());
        store.update("shop", "users", 1L, new Object[] {2, "t2-r2"});
        tx.rollbackExplicit(lock, catalog, store);

        assertEquals("two", heap.findByRowId("shop", "users", 1L).orElseThrow().values()[1]);
    }

    private static final class FakeWal implements com.example.database.storage.wal.WALManager {
        @Override
        public void append(com.example.database.storage.wal.WalRecord record) {
        }

        @Override
        public long appendReturningLsn(com.example.database.storage.wal.WalRecord record) {
            return 1L;
        }

        @Override
        public void flush() {
        }

        @Override
        public void flushUpTo(long lsn) {
        }

        @Override
        public void discardPending() {
        }

        @Override
        public int replay(com.example.database.storage.catalog.CatalogManager catalogManager) {
            return 0;
        }

        @Override
        public void redoDml(
                com.example.database.storage.table.TableStore tableStore,
                com.example.database.storage.index.IndexStore indexStore
        ) {
        }

        @Override
        public int checkpoint() {
            return 0;
        }
    }
}
