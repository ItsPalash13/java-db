package com.example.database.storage.undo;

/**
 * One inverse operation for {@link UndoManager#rollback(int, com.example.database.storage.table.TableStore)}.
 */
public sealed interface UndoRecord {

    record DeleteInsert(String database, String table, long rowId) implements UndoRecord {
    }

    record RestoreUpdate(String database, String table, long rowId, Object[] beforeValues) implements UndoRecord {
    }

    record RestoreDelete(String database, String table, long rowId, Object[] values) implements UndoRecord {
    }
}
