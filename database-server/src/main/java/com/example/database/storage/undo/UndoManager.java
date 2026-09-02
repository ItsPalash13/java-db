package com.example.database.storage.undo;

import com.example.database.storage.table.TableStore;

/**
 * Records before-images for DML and rolls back a transaction in reverse order.
 * Owned by {@link com.example.database.storage.transaction.TransactionManager}, not LockManager.
 */
public interface UndoManager {

    void recordInsert(int txnId, String database, String table, long rowId);

    void recordUpdate(int txnId, String database, String table, long rowId, Object[] beforeValues);

    void recordDelete(int txnId, String database, String table, long rowId, Object[] beforeValues);

    /** Applies undo records for {@code txnId} then drops them. */
    void rollback(int txnId, TableStore tableStore);

    /** Drops undo records after successful commit. */
    void clear(int txnId);
}
