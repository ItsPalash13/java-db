package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    @Override
    public TableSnapshot snapshot() {
        Map<String, List<Tuple>> copy = new HashMap<>();
        for (Map.Entry<String, List<Tuple>> entry : tables.entrySet()) {
            List<Tuple> rows = entry.getValue();
            synchronized (rows) {
                copy.put(entry.getKey(), copyRows(rows));
            }
        }
        return new TableSnapshot(copy, nextRowId.get());
    }

    @Override
    public void restoreSnapshot(TableSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        tables.clear();
        for (Map.Entry<String, List<Tuple>> entry : snapshot.tablesByKey().entrySet()) {
            tables.put(entry.getKey(), Collections.synchronizedList(copyRows(entry.getValue())));
        }
        nextRowId.set(snapshot.nextRowId());
    }

    @Override
    public Optional<Tuple> findByRowId(String database, String table, long rowId) {
        List<Tuple> rows = tables.get(key(database, table));
        if (rows == null) {
            return Optional.empty();
        }
        synchronized (rows) {
            for (Tuple row : rows) {
                if (row.rowId() == rowId) {
                    return Optional.of(row);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void restoreRow(String database, String table, Tuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        String tableKey = key(database, table);
        List<Tuple> rows = tables.computeIfAbsent(tableKey, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (rows) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).rowId() == tuple.rowId()) {
                    rows.set(i, new Tuple(tuple.rowId(), tuple.values().clone()));
                    return;
                }
            }
            rows.add(new Tuple(tuple.rowId(), tuple.values().clone()));
        }
        // Undo may restore a high rowId after other inserts in the same txn — keep counter monotonic.
        nextRowId.updateAndGet(current -> Math.max(current, tuple.rowId() + 1));
    }

    private static List<Tuple> copyRows(List<Tuple> rows) {
        List<Tuple> copy = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            Object[] values = row.values();
            copy.add(new Tuple(row.rowId(), values.clone()));
        }
        return copy;
    }

    private static String key(String database, String table) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        return database + "." + table;
    }
}
