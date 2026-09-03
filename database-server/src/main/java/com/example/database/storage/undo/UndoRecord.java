package com.example.database.storage.undo;

import com.example.database.storage.page.Rid;

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

    record IndexDelete(
            String database,
            String table,
            String indexName,
            Object[] key,
            Rid rid
    ) implements UndoRecord {
    }

    record IndexInsert(
            String database,
            String table,
            String indexName,
            Object[] key,
            Rid rid
    ) implements UndoRecord {
    }
}
