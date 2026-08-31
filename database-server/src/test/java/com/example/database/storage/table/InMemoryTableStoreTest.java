package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryTableStoreTest {

    @Test
    void insertScanUpdateDeleteAndDrop() {
        InMemoryTableStore store = new InMemoryTableStore();

        Tuple first = store.insert("shop", "users", new Object[]{1, "Ada"});
        Tuple second = store.insert("shop", "users", new Object[]{2, "Bob"});
        assertEquals(1L, first.rowId());
        assertEquals(2L, second.rowId());

        List<Tuple> scanned = collect(store.scan("shop", "users"));
        assertEquals(2, scanned.size());
        assertEquals("Ada", scanned.get(0).get(2));

        store.update("shop", "users", first.rowId(), new Object[]{1, "Ada Lovelace"});
        assertEquals("Ada Lovelace", collect(store.scan("shop", "users")).get(0).get(2));

        store.delete("shop", "users", second.rowId());
        assertEquals(1, collect(store.scan("shop", "users")).size());

        store.dropTable("shop", "users");
        assertFalse(store.scan("shop", "users").hasNext());

        store.insert("shop", "users", new Object[]{9, "X"});
        store.insert("shop", "orders", new Object[]{1});
        store.dropDatabase("shop");
        assertFalse(store.scan("shop", "users").hasNext());
        assertFalse(store.scan("shop", "orders").hasNext());
    }

    private static List<Tuple> collect(Iterator<Tuple> iterator) {
        List<Tuple> rows = new ArrayList<>();
        iterator.forEachRemaining(rows::add);
        return rows;
    }
}
