package com.example.database.storage.lock;

import com.example.database.config.ServerEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Central lock manager for catalog exclusivity and scoped SQL locks (database / table / row).
 * <p>
 * Two separate synchronization domains:
 * <ul>
 *   <li>{@link #catalogLock} — one global exclusive lock for catalog file I/O (CHECKPOINT, CREATE DATABASE).</li>
 *   <li>{@link #stateMutex} + {@link #states} — the in-memory <em>lock table</em>: one {@link LockState}
 *       per {@link LockKey}. This mutex is a latch on the manager's map only; it is <em>not</em> a SQL table lock.</li>
 * </ul>
 * Hierarchy (db intention → table → row) is enforced by acquire order in public methods, not by nesting
 * map entries. Each {@link LockKey} is an independent entry in {@link #states}.
 */
public final class DefaultLockManager implements LockManager {

    static final Duration DEFAULT_CATALOG_LOCK_WAIT =
            Duration.ofSeconds(ServerEnvironment.DEFAULT_CATALOG_LOCK_WAIT_SECONDS);

    // Catalog path: separate from scoped locks so short DDL can use catalog X without touching the lock table.
    private final ReentrantLock catalogLock = new ReentrantLock();

    // Protects states, heldByOwner, and every LockState.waiters queue. Threads park on per-waiter
    // Conditions while this mutex is released — otherwise no other txn could grant or release locks.
    private final ReentrantLock stateMutex = new ReentrantLock();

    private final Duration lockWait;
    private final DeadlockMode deadlockMode;
    private final DeadlockPrevention prevention;
    private final DeadlockResolution resolution;

    // Flat map: one LockState per lockable object (catalog key, database, table, or row).
    private final Map<LockKey, LockState> states = new HashMap<>();

    // Reverse index so unlockAllForOwner() can drop every scoped lock for a txn without scanning all keys.
    private final Map<Long, List<HeldLock>> heldByOwner = new HashMap<>();

    // Wound-Wait sets this; Wait-Die throws before wait. Checked on every acquire loop iteration.
    private final Map<Long, AtomicBoolean> abortFlags = new HashMap<>();

    // When set (via bindOwner), lock owner id is txnId — not thread id — so locks follow the transaction.
    private final ThreadLocal<Long> boundOwner = new ThreadLocal<>();

    public DefaultLockManager() {
        this(DEFAULT_CATALOG_LOCK_WAIT);
    }

    public DefaultLockManager(Duration lockWait) {
        this(lockWait, DeadlockMode.PREVENT, DeadlockPrevention.WAIT_DIE, DeadlockResolution.ABORT_YOUNGEST);
    }

    public DefaultLockManager(
            Duration lockWait,
            DeadlockMode deadlockMode,
            DeadlockPrevention prevention,
            DeadlockResolution resolution
    ) {
        this.lockWait = Objects.requireNonNull(lockWait, "lockWait");
        if (lockWait.isNegative() || lockWait.isZero()) {
            throw new IllegalArgumentException("lockWait must be positive");
        }
        this.deadlockMode = Objects.requireNonNull(deadlockMode, "deadlockMode");
        this.prevention = Objects.requireNonNull(prevention, "prevention");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    Duration catalogLockWait() {
        return lockWait;
    }

    // --- Owner binding (txn id as lock owner) ---------------------------------

    @Override
    public void bindOwner(long ownerId) {
        boundOwner.set(ownerId);
        // Pre-create abort flag so woundOwner can flip it without racing bindOwner on first conflict.
        abortFlags.computeIfAbsent(ownerId, ignored -> new AtomicBoolean(false));
    }

    @Override
    public void clearOwnerBinding() {
        boundOwner.remove();
        // abortFlags intentionally retained: a wounded txn may still be in await(); checkNotAborted reads the flag.
    }

    // --- Catalog exclusive lock (legacy path, unchanged semantics) ----------

    @Override
    public void runExclusiveCatalog(Runnable action) {
        Objects.requireNonNull(action, "action");
        runExclusiveCatalog(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T runExclusiveCatalog(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        acquireCatalogLock();
        try {
            return action.get();
        } finally {
            catalogLock.unlock();
        }
    }

    @Override
    public void lockExclusiveCatalog() {
        acquireCatalogLock();
    }

    @Override
    public void unlockExclusiveCatalog() {
        catalogLock.unlock();
    }

    // --- Scoped locks: table / database (with hierarchy) --------------------

    @Override
    public void runWithTable(String database, String table, LockMode tableMode, Runnable action) {
        runWithTable(database, table, tableMode, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T runWithTable(String database, String table, LockMode tableMode, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        validateTableMode(tableMode);
        long owner = currentOwner();
        // Intention on parent (db IS for table S, db IX for table X/IX) so compatibility matrix applies across levels.
        LockMode intention = LockMode.intentionFor(tableMode);
        acquire(LockKey.database(database), intention, owner);
        acquire(LockKey.table(database, table), tableMode, owner);
        try {
            return action.get();
        } finally {
            // Child released before parent so a waiter on the table key does not see stale db intention.
            release(LockKey.table(database, table), tableMode, owner);
            release(LockKey.database(database), intention, owner);
        }
    }

    @Override
    public void runWithDatabase(String database, LockMode mode, Runnable action) {
        runWithDatabase(database, mode, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T runWithDatabase(String database, LockMode mode, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (mode != LockMode.X) {
            throw new IllegalArgumentException("runWithDatabase expects X, got " + mode);
        }
        long owner = currentOwner();
        acquire(LockKey.database(database), mode, owner);
        try {
            return action.get();
        } finally {
            release(LockKey.database(database), mode, owner);
        }
    }

    /**
     * Acquire table lock and matching database intention; caller must later call
     * {@link #unlockTable} or {@link #unlockAllForOwner}. Used when locks span multiple
     * row acquires inside one statement (e.g. VolcanoExecutor UPDATE).
     */
    @Override
    public void lockTable(String database, String table, LockMode tableMode) {
        validateTableMode(tableMode);
        long owner = currentOwner();
        LockMode intention = LockMode.intentionFor(tableMode);
        acquire(LockKey.database(database), intention, owner);
        acquire(LockKey.table(database, table), tableMode, owner);
    }

    @Override
    public void unlockTable(String database, String table, LockMode tableMode) {
        long owner = currentOwner();
        LockMode intention = LockMode.intentionFor(tableMode);
        release(LockKey.table(database, table), tableMode, owner);
        release(LockKey.database(database), intention, owner);
    }

    // --- Row locks (no automatic parent acquire — caller must hold table IS/IX already) ---

    @Override
    public void lockRow(String database, String table, long rowId, LockMode mode) {
        if (mode != LockMode.S && mode != LockMode.X) {
            throw new IllegalArgumentException("lockRow expects S or X, got " + mode);
        }
        acquire(LockKey.row(database, table, rowId), mode, currentOwner());
    }

    @Override
    public void unlockRow(String database, String table, long rowId, LockMode mode) {
        release(LockKey.row(database, table, rowId), mode, currentOwner());
    }

    /**
     * Drops every scoped lock recorded for the current owner. Used at end of statement
     * (VolcanoExecutor finally) and on lock failure so a partial acquire does not leak.
     */
    @Override
    public void unlockAllForOwner() {
        long owner = currentOwner();
        // Snapshot under mutex, then release outside the bulk snapshot lock — each release() re-locks stateMutex.
        List<HeldLock> snapshot;
        stateMutex.lock();
        try {
            snapshot = new ArrayList<>(heldByOwner.getOrDefault(owner, List.of()));
        } finally {
            stateMutex.unlock();
        }
        // Reverse order: row locks before table before db intention (LIFO matches typical acquire stack).
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            HeldLock held = snapshot.get(i);
            release(held.key(), held.mode(), owner);
        }
    }

    /**
     * Lock owner id: bound txn id when inside runInTransaction + bindOwner, else thread id
     * (tests / catalog paths that do not bind).
     */
    private long currentOwner() {
        Long bound = boundOwner.get();
        if (bound != null) {
            return bound;
        }
        return Thread.currentThread().getId();
    }

    private static void validateTableMode(LockMode tableMode) {
        if (tableMode != LockMode.S && tableMode != LockMode.X && tableMode != LockMode.IS && tableMode != LockMode.IX) {
            throw new IllegalArgumentException("unsupported table mode: " + tableMode);
        }
    }

    // --- Core acquire / release (lock table engine) ---------------------------

    /**
     * Grant {@code mode} on {@code key} to {@code ownerId}, blocking with timeout if incompatible
     * holders exist. Must be called with a clear owner (bindOwner or thread id).
     * <p>
     * Blocking uses a per-request {@link LockWaiter} and {@link Condition} so the thread can park
     * without holding {@link #stateMutex} — otherwise the lock manager would deadlock itself.
     */
    private void acquire(LockKey key, LockMode mode, long ownerId) {
        stateMutex.lock();
        try {
            checkNotAborted(ownerId);
            LockState state = states.computeIfAbsent(key, ignored -> new LockState());

            // Re-entrant same owner + same mode: bump refcount without re-checking compatibility.
            if (state.holderCount(ownerId, mode) > 0) {
                state.grant(ownerId, mode);
                recordHeld(ownerId, key, mode);
                return;
            }

            long deadlineNanos = System.nanoTime() + lockWait.toNanos();
            while (true) {
                // Fast path: no conflicting holders on this key.
                if (LockMode.canGrant(state, mode)) {
                    state.grant(ownerId, mode);
                    recordHeld(ownerId, key, mode);
                    return;
                }

                // Wait-Die / Wound-Wait run only for ROW keys; table/db conflicts use plain FIFO wait.
                // Younger txn aborting on row wait avoids cycles without a wait-for graph.
                handleConflictBeforeWait(ownerId, key, mode, state);

                // Enqueue then park. Condition is tied to stateMutex (Java lock/condition pairing rule).
                LockWaiter waiter = new LockWaiter(ownerId, mode, stateMutex.newCondition());
                state.waiters().addLast(waiter);
                try {
                    while (!waiter.granted) {
                        checkNotAborted(ownerId);
                        long remainingNanos = deadlineNanos - System.nanoTime();
                        if (remainingNanos <= 0) {
                            throw timeout();
                        }
                        // Releases stateMutex while parked; re-acquires before return.
                        if (!waiter.condition.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                            throw timeout();
                        }
                        // grantWaiters may have set granted and recorded the lock before we woke.
                        if (waiter.granted) {
                            return;
                        }
                        // Spurious wakeup or signal without grant: re-check compatibility.
                        if (LockMode.canGrant(state, mode)) {
                            state.grant(ownerId, mode);
                            recordHeld(ownerId, key, mode);
                            waiter.granted = true;
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockException("lock wait interrupted", e);
                } finally {
                    // Always dequeue: on timeout/abort the waiter must not stay in FIFO queue.
                    state.waiters().remove(waiter);
                }
            }
        } finally {
            stateMutex.unlock();
        }
    }

    /**
     * Drop one grant for {@code ownerId} on {@code key}. Wakes compatible waiters via {@link #grantWaiters}.
     * Idempotent if owner did not hold the mode (caller mismatch / double unlock).
     */
    private void release(LockKey key, LockMode mode, long ownerId) {
        stateMutex.lock();
        try {
            LockState state = states.get(key);
            if (state == null || state.holderCount(ownerId, mode) == 0) {
                return;
            }
            state.release(ownerId, mode);
            removeHeld(ownerId, key, mode);
            // Evict empty LockState entries so the map does not grow forever for one-off row locks.
            if (state.count(LockMode.IS) == 0
                    && state.count(LockMode.IX) == 0
                    && state.count(LockMode.S) == 0
                    && state.count(LockMode.X) == 0
                    && state.waiters().isEmpty()) {
                states.remove(key);
            } else {
                grantWaiters(key, state);
            }
        } finally {
            stateMutex.unlock();
        }
    }

    /**
     * After a release, grant as many FIFO waiters as compatibility allows.
     * Stops at first waiter that still conflicts, or after granting X (exclusive blocks everyone else).
     */
    private void grantWaiters(LockKey key, LockState state) {
        Iterator<LockWaiter> iterator = state.waiters().iterator();
        while (iterator.hasNext()) {
            LockWaiter waiter = iterator.next();
            if (!LockMode.canGrant(state, waiter.requestedMode)) {
                break;
            }
            state.grant(waiter.ownerId, waiter.requestedMode);
            recordHeld(waiter.ownerId, key, waiter.requestedMode);
            waiter.granted = true;
            waiter.condition.signal();
            iterator.remove();
            // One X holder — do not grant further waiters in this pass (they need another release).
            if (waiter.requestedMode == LockMode.X) {
                break;
            }
        }
    }

    /**
     * Deadlock <em>prevention</em> hook invoked immediately before enqueue + await.
     * Does not grant locks; either throws (Wait-Die), wounds (Wound-Wait), or returns to allow wait.
     * <p>
     * Only ROW level: applying Wait-Die to table X made DDL/DML tests abort instead of queue
     * (younger txn "would wait" on older table X holder). Row scope limits prevention to real row cycles.
     */
    private void handleConflictBeforeWait(long ownerId, LockKey key, LockMode mode, LockState state) {
        if (deadlockMode == DeadlockMode.PREVENT && key.level() == LockLevel.ROW) {
            if (prevention == DeadlockPrevention.WAIT_DIE) {
                // Lower ownerId = older txn (txn ids monotonically increase). Younger must die, not wait.
                for (long holder : state.conflictingHolders(mode)) {
                    if (holder < ownerId) {
                        throw new TransactionAbortedException(
                                "transaction aborted (wait-die): txn " + ownerId + " would wait on older txn " + holder
                        );
                    }
                }
                return;
            }
            if (prevention == DeadlockPrevention.WOUND_WAIT) {
                // Older waiter wounds younger holders so it can wait without creating a cycle.
                for (long holder : new ArrayList<>(state.conflictingHolders(mode))) {
                    if (holder > ownerId) {
                        woundOwner(holder);
                    }
                }
                return;
            }
        }
        // DETECT_RESOLVE: build wait-for graph, pick victim per resolution policy — not implemented yet.
    }

    /**
     * Mark txn as aborted and wake any of its parked waiters so they observe the flag in checkNotAborted.
     * Does not release held locks here — caller / unlockAllForOwner / rollback path must clean up.
     */
    private void woundOwner(long ownerId) {
        AtomicBoolean flag = abortFlags.computeIfAbsent(ownerId, ignored -> new AtomicBoolean(false));
        flag.set(true);
        stateMutex.lock();
        try {
            for (LockState state : states.values()) {
                for (LockWaiter waiter : state.waiters()) {
                    if (waiter.ownerId == ownerId) {
                        waiter.condition.signal();
                    }
                }
            }
        } finally {
            stateMutex.unlock();
        }
    }

    private void checkNotAborted(long ownerId) {
        AtomicBoolean flag = abortFlags.get(ownerId);
        if (flag != null && flag.get()) {
            throw new TransactionAbortedException("transaction aborted: txn " + ownerId);
        }
    }

    private void recordHeld(long ownerId, LockKey key, LockMode mode) {
        heldByOwner.computeIfAbsent(ownerId, ignored -> new ArrayList<>()).add(new HeldLock(key, mode));
    }

    private void removeHeld(long ownerId, LockKey key, LockMode mode) {
        List<HeldLock> held = heldByOwner.get(ownerId);
        if (held == null) {
            return;
        }
        // Remove one matching entry (refcount may have multiple grants of same key+mode).
        for (int i = held.size() - 1; i >= 0; i--) {
            HeldLock entry = held.get(i);
            if (entry.key().equals(key) && entry.mode() == mode) {
                held.remove(i);
                break;
            }
        }
        if (held.isEmpty()) {
            heldByOwner.remove(ownerId);
        }
    }

    private CatalogLockException timeout() {
        return new CatalogLockException("lock wait timed out after " + lockWait.toSeconds() + "s");
    }

    /**
     * Catalog lock uses tryLock + timeout instead of the lock-table Condition path — catalog is
     * a single ReentrantLock, not an entry in {@link #states}.
     */
    private void acquireCatalogLock() {
        try {
            if (!catalogLock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new CatalogLockException(
                        "catalog lock wait timed out after " + lockWait.toSeconds() + "s"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogLockException("catalog lock wait interrupted", e);
        }
    }
}
