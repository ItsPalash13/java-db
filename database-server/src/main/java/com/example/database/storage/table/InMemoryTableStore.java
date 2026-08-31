package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temporary RAM heap: {@code Map<"shop.users", ArrayList&lt;Tuple&gt;}.
 * Lost on process restart — not durable and not WAL-logged. Exists only so
 * Volcano can INSERT/SELECT before Page / BufferPool exist.
 */
public final class InMemoryTableStore implements TableStore {

    private final Map<String, List<Tuple>> tables = new ConcurrentHashMap<>();
    private final AtomicLong nextRowId = new AtomicLong(1);

    @Override
    public Tuple insert(String database, String table, Object[] values) {
        Objects.requireNonNull(values, "values");
        String key = key(database, table);
        long rowId = nextRowId.getAndIncrement();
        Tuple tuple = new Tuple(rowId, values);
        tables.computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>())).add(tuple);
        return tuple;
    }

    @Override
    public Iterator<Tuple> scan(String database, String table) {
        List<Tuple> rows = tables.get(key(database, table));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyIterator();
        }
        // Snapshot so UPDATE/DELETE during a scan do not ConcurrentModificationException.
        synchronized (rows) {
            return List.copyOf(rows).iterator();
        }
    }

    @Override
    public void update(String database, String table, long rowId, Object[] values) {
        Objects.requireNonNull(values, "values");
        List<Tuple> rows = tables.get(key(database, table));
        if (rows == null) {
            return;
        }
        synchronized (rows) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).rowId() == rowId) {
                    rows.set(i, new Tuple(rowId, values));
                    return;
                }
            }
        }
    }

    @Override
    public void delete(String database, String table, long rowId) {
        List<Tuple> rows = tables.get(key(database, table));
        if (rows == null) {
            return;
        }
        synchronized (rows) {
            rows.removeIf(tuple -> tuple.rowId() == rowId);
        }
    }

    @Override
    public void dropTable(String database, String table) {
        tables.remove(key(database, table));
    }

    @Override
    public void dropDatabase(String database) {
        Objects.requireNonNull(database, "database");
        String prefix = database + ".";
        tables.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String key(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        return database + "." + table;
    }
}
