package com.example.database.storage.lock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-key grant counts, per-owner refcounts, and FIFO waiters. Mutated only under
 * {@link DefaultLockManager}'s state mutex.
 */
final class LockState {

    private final EnumMap<LockMode, Integer> counts = new EnumMap<>(LockMode.class);
    private final Map<Long, EnumMap<LockMode, Integer>> holders = new HashMap<>();
    private final ArrayDeque<LockWaiter> waiters = new ArrayDeque<>();

    LockState() {
        for (LockMode mode : LockMode.values()) {
            counts.put(mode, 0);
        }
    }

    int count(LockMode mode) {
        return counts.getOrDefault(mode, 0);
    }

    int holderCount(long ownerId, LockMode mode) {
        EnumMap<LockMode, Integer> modes = holders.get(ownerId);
        if (modes == null) {
            return 0;
        }
        return modes.getOrDefault(mode, 0);
    }

    void grant(long ownerId, LockMode mode) {
        counts.merge(mode, 1, Integer::sum);
        holders.computeIfAbsent(ownerId, ignored -> new EnumMap<>(LockMode.class))
                .merge(mode, 1, Integer::sum);
    }

    void release(long ownerId, LockMode mode) {
        int ownerHeld = holderCount(ownerId, mode);
        if (ownerHeld <= 0) {
            throw new IllegalStateException("owner " + ownerId + " does not hold " + mode);
        }
        if (ownerHeld == 1) {
            EnumMap<LockMode, Integer> modes = holders.get(ownerId);
            modes.remove(mode);
            if (modes.isEmpty()) {
                holders.remove(ownerId);
            }
        } else {
            holders.get(ownerId).merge(mode, -1, Integer::sum);
        }
        int total = count(mode);
        if (total <= 0) {
            throw new IllegalStateException("lock count underflow for " + mode);
        }
        if (total == 1) {
            counts.put(mode, 0);
        } else {
            counts.merge(mode, -1, Integer::sum);
        }
    }

    ArrayDeque<LockWaiter> waiters() {
        return waiters;
    }

    /** Owners holding a mode that conflicts with {@code requested}. */
    List<Long> conflictingHolders(LockMode requested) {
        List<Long> result = new ArrayList<>();
        for (Map.Entry<Long, EnumMap<LockMode, Integer>> entry : holders.entrySet()) {
            long ownerId = entry.getKey();
            for (Map.Entry<LockMode, Integer> grant : entry.getValue().entrySet()) {
                if (grant.getValue() > 0 && !LockMode.compatible(grant.getKey(), requested)) {
                    result.add(ownerId);
                    break;
                }
            }
        }
        return result;
    }
}
