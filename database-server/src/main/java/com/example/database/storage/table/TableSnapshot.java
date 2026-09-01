package com.example.database.storage.table;

import com.example.database.processor.executor.engine.volcano.Tuple;

import java.util.List;
import java.util.Map;

/**
 * Point-in-time copy of heap rows for explicit transaction rollback.
 * {@link InMemoryTableStore} only today; a file store would snapshot pages or undo logs later.
 */
public record TableSnapshot(Map<String, List<Tuple>> tablesByKey, long nextRowId) {
}
