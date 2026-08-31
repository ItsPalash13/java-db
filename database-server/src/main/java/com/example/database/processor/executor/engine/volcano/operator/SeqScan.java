package com.example.database.processor.executor.engine.volcano.operator;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.table.TableStore;

import java.util.Iterator;
import java.util.Objects;

/**
 * Full heap scan. INDEX_SCAN plans also use this until IndexStore / B+Tree exist;
 * Filter applies the WHERE predicate after the scan.
 */
public final class SeqScan implements VolcanoOperator {

    private final TableStore tableStore;
    private final String database;
    private final String table;
    private Iterator<Tuple> iterator;

    public SeqScan(TableStore tableStore, String database, String table) {
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public void open() {
        iterator = tableStore.scan(database, table);
    }

    @Override
    public Tuple next() {
        if (iterator == null || !iterator.hasNext()) {
            return null;
        }
        return iterator.next();
    }

    @Override
    public void close() {
        iterator = null;
    }
}
