package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.page.Rid;
import com.example.database.storage.transaction.TransactionManager;
import com.example.database.storage.undo.UndoManager;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Records undo entries for active transactions before delegating DML to the inner store.
 * Volcano reads/writes through this wrapper so rollback does not need a full heap snapshot.
 */
public final class UndoableTableStore implements TableStore {

    private final TableStore delegate;
    private final UndoManager undoManager;
    private final TransactionManager transactionManager;

    public UndoableTableStore(
            TableStore delegate,
            UndoManager undoManager,
            TransactionManager transactionManager
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
    }

    /** Inner heap for tests that need direct access without undo wrapping. */
    public TableStore delegate() {
        return delegate;
    }

    @Override
    public Tuple insert(String database, String table, Object[] values) {
        Tuple inserted = delegate.insert(database, table, values);
        if (transactionManager.active()) {
            undoManager.recordInsert(transactionManager.currentTxnId(), database, table, inserted.rowId());
        }
        return inserted;
    }

    @Override
    public Iterator<Tuple> scan(String database, String table) {
        return delegate.scan(database, table);
    }

    @Override
    public void update(String database, String table, long rowId, Object[] values) {
        if (transactionManager.active()) {
            Optional<Tuple> before = delegate.findByRowId(database, table, rowId);
            before.ifPresent(tuple -> undoManager.recordUpdate(
                    transactionManager.currentTxnId(),
                    database,
                    table,
                    rowId,
                    tuple.values()
            ));
        }
        delegate.update(database, table, rowId, values);
    }

    @Override
    public void delete(String database, String table, long rowId) {
        if (transactionManager.active()) {
            delegate.findByRowId(database, table, rowId).ifPresent(tuple -> undoManager.recordDelete(
                    transactionManager.currentTxnId(),
                    database,
                    table,
                    rowId,
                    tuple.values()
            ));
        }
        delegate.delete(database, table, rowId);
    }

    @Override
    public void dropTable(String database, String table) {
        delegate.dropTable(database, table);
    }

    @Override
    public void dropDatabase(String database) {
        delegate.dropDatabase(database);
    }

    @Override
    public TableSnapshot snapshot() {
        return delegate.snapshot();
    }

    @Override
    public void restoreSnapshot(TableSnapshot snapshot) {
        delegate.restoreSnapshot(snapshot);
    }

    @Override
    public Optional<Tuple> findByRowId(String database, String table, long rowId) {
        return delegate.findByRowId(database, table, rowId);
    }

    @Override
    public Optional<Rid> findRid(String database, String table, long rowId) {
        return delegate.findRid(database, table, rowId);
    }

    @Override
    public Optional<Tuple> findByRid(String database, String table, Rid rid) {
        return delegate.findByRid(database, table, rid);
    }

    @Override
    public void restoreRow(String database, String table, Tuple tuple) {
        delegate.restoreRow(database, table, tuple);
    }
}
