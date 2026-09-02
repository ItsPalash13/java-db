package com.example.database.storage.page;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAM-only {@link RidMap} for codec tests and Phase 2. Not durable — a restart
 * loses it. Phase 4's file heap will replace or back this with on-disk state.
 */
public final class InMemoryRidMap implements RidMap {

    private final ConcurrentHashMap<Long, Rid> byRowId = new ConcurrentHashMap<>();

    @Override
    public void put(long rowId, Rid rid) {
        Objects.requireNonNull(rid, "rid");
        byRowId.put(rowId, rid);
    }

    @Override
    public Optional<Rid> get(long rowId) {
        return Optional.ofNullable(byRowId.get(rowId));
    }

    @Override
    public void remove(long rowId) {
        byRowId.remove(rowId);
    }

    @Override
    public void clear() {
        byRowId.clear();
    }
}
