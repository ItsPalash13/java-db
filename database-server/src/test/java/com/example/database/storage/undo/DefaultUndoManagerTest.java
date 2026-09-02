package com.example.database.storage.undo;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.table.InMemoryTableStore;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultUndoManagerTest {

    @Test
    void rollbackReversesInsertUpdateDelete() {
        InMemoryTableStore store = new InMemoryTableStore();
        DefaultUndoManager undo = new DefaultUndoManager();
        int txn = 1;

        Tuple inserted = store.insert("shop", "users", new Object[] {1, "before"});
        undo.recordInsert(txn, "shop", "users", inserted.rowId());

        undo.recordUpdate(txn, "shop", "users", inserted.rowId(), new Object[] {1, "before"});
        store.update("shop", "users", inserted.rowId(), new Object[] {1, "after"});

        undo.recordDelete(txn, "shop", "users", inserted.rowId(), new Object[] {1, "after"});
        store.delete("shop", "users", inserted.rowId());

        undo.rollback(txn, store);

        Iterator<Tuple> rows = store.scan("shop", "users");
        assertFalse(rows.hasNext());
    }

    @Test
    void rollbackRestoresDeletedRow() {
        InMemoryTableStore store = new InMemoryTableStore();
        DefaultUndoManager undo = new DefaultUndoManager();
        Tuple row = store.insert("shop", "users", new Object[] {7, "keep"});

        undo.recordDelete(txn(), "shop", "users", row.rowId(), row.values());
        store.delete("shop", "users", row.rowId());

        undo.rollback(1, store);

        Tuple restored = store.findByRowId("shop", "users", row.rowId()).orElseThrow();
        assertEquals(7, restored.values()[0]);
        assertEquals("keep", restored.values()[1]);
    }

    private static int txn() {
        return 1;
    }
}
