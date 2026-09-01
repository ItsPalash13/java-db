package com.example.database.storage.lock;

import java.util.concurrent.locks.Condition;

/**
 * One blocked acquire on a single {@link LockKey}. Created in {@link DefaultLockManager#acquire}
 * when {@link LockMode#canGrant} is false; the thread parks on {@link #condition} until
 * {@link DefaultLockManager#grantWaiters} sets {@link #granted} and calls {@code signal()}.
 * <p>
 * Example — three txns on {@code shop.orders} row {@code 7} (2 UPDATE, 1 SELECT).
 * Wait-Die applies at row level only; lower txn id = older txn.
 *
 * <pre>
 * ┌──────┬─────────┬──────────────────────────────────────────────────────────────────────────────┐
 * │ Txn  │ Statement │ Locks acquired (db → table → row)                                          │
 * ├──────┼─────────┼──────────────────────────────────────────────────────────────────────────────┤
 * │ 100  │ SELECT  │ db IS, table IS, row 7 S  (reader — {@link VolcanoExecutor#executeSelect}) │
 * │ 200  │ UPDATE  │ db IX, table IX, row 7 X  (writer — {@link VolcanoExecutor#executeUpdate}) │
 * │ 300  │ UPDATE  │ db IX, table IX, row 7 X  (writer — same path as txn 200)                    │
 * └──────┴─────────┴──────────────────────────────────────────────────────────────────────────────┘
 *
 * Timeline (same row key: LockKey.row("shop","orders",7)):
 *
 * ┌──────┬───────────┬─────────────────────┬─────────────────────┬───────────────────────────────┐
 * │ Step │ Txn 100   │ Txn 200             │ Txn 300             │ row 7 LockState               │
 * │      │ SELECT    │ UPDATE              │ UPDATE              │ (holders + waiters queue)     │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T1  │ db IS ✓   │                     │ db IX ✓             │ (no row entry yet)            │
 * │      │ table IS ✓│                     │ table IX ✓          │ table/db IX+IS compatible     │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T2  │           │                     │ row 7 X ✓ granted   │ X: {300}                      │
 * │      │           │                     │ (updating…)         │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T3  │ row 7 S   │ db IX ✓             │ still holds row X   │ X: {300}                      │
 * │      │ blocked   │ table IX ✓          │                     │                               │
 * │      │ Wait-Die: │                     │                     │                               │
 * │      │ holder 300│                     │                     │                               │
 * │      │ ≮ 100 →   │                     │                     │                               │
 * │      │ wait OK   │                     │                     │                               │
 * │      │ → W100    │                     │                     │ waiters: [W100(S)]            │
 * │      │ await()   │                     │                     │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T4  │ (parked)  │ row 7 X blocked     │ still holds row X   │ X: {300}                      │
 * │      │           │ Wait-Die: 300 ≮ 200 │                     │ waiters: [W100(S), W200(X)]   │
 * │      │           │ → wait OK → W200    │                     │                               │
 * │      │           │ await()             │                     │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T5  │ (parked)  │ (parked)            │ unlockRow X         │ waiters: [W100, W200]         │
 * │      │           │                     │                     │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T6  │ W100      │ (parked)            │ done                │ S: {100}  grantWaiters: FIFO  │
 * │      │ granted S │                     │                     │ first compatible waiter wins  │
 * │      │ signal()  │                     │                     │                               │
 * │      │ reading…  │                     │                     │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T7  │ holds S   │ (parked)            │ done                │ S: {100}                      │
 * │      │           │                     │                     │ W200 still waits (S blocks X) │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T8  │ unlockRow │ (parked)            │ done                │ waiters: [W200(X)]            │
 * │      │ S         │                     │                     │                               │
 * ├──────┼───────────┼─────────────────────┼─────────────────────┼───────────────────────────────┤
 * │  T9  │ done      │ W200 granted X      │ done                │ X: {200}                      │
 * │      │           │ signal() → update   │                     │                               │
 * └──────┴───────────┴─────────────────────┴─────────────────────┴───────────────────────────────┘
 *
 * LockWaiter fields in this example:
 *   W100 → ownerId=100, requestedMode=S, condition=stateMutex.newCondition(), granted=false until T6
 *   W200 → ownerId=200, requestedMode=X, condition=stateMutex.newCondition(), granted=false until T9
 *
 * Notes:
 *   • db/table locks are separate LockKeys — all three txns can hold IS/IX on the same table concurrently.
 *   • Only row 7 conflicts queue LockWaiters; each waiter gets its own Condition (same stateMutex, not shared).
 *   • If txn 100 were younger than holder 300 (e.g. txn 400 waiting on 300), Wait-Die would abort — no LockWaiter.
 *   • await() releases stateMutex while parked so txn 300 can release row X and grantWaiters can signal W100.
 * </pre>
 */
final class LockWaiter {

    /** Txn id (from bindOwner) — also used by Wait-Die (lower id = older txn). */
    final long ownerId;

    /** S or X at row level; IS/IX/S/X at table/db when those acquires block. */
    final LockMode requestedMode;

    /**
     * Parking spot for this waiter only. Created via {@code stateMutex.newCondition()} — must be
     * the lock held during await/signal (Java Condition pairing rule), not a SQL row lock.
     */
    final Condition condition;

    /** Set true by grantWaiters before signal(); exiting acquire() loop when true. */
    boolean granted;

    LockWaiter(long ownerId, LockMode requestedMode, Condition condition) {
        this.ownerId = ownerId;
        this.requestedMode = requestedMode;
        this.condition = condition;
    }
}
