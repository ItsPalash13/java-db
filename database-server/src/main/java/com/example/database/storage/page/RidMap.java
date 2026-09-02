package com.example.database.storage.page;

import java.util.Optional;

/**
 * Maps the logical {@code rowId} used by locks/undo to a heap {@link Rid}.
 * Phase 2 keeps this as an in-memory structure; a durable file heap (Phase 4)
 * will own persistence of the same mapping.
 */
public interface RidMap {

    void put(long rowId, Rid rid);

    Optional<Rid> get(long rowId);

    void remove(long rowId);

    void clear();
}
