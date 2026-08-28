# Lock timing: analyser vs execution

**Decision:** take locks at **execution**, not in the analyser alone.

The analyser may read the catalog for early, user-friendly errors. Anything that must still be true when we mutate catalog or row data must be **re-checked under the lock** at execute—or we hold one lock across analyse → execute for DDL in Phase 1.

---

## Pipeline today

```
lex / parse  →  no locks (pure syntax)
analyse      →  reads catalog (tableExists, databaseExists, …)
plan         →  no locks (builds ExecutionPlan)
execute      →  writes catalog today; will read/write rows later
```

The gap between **analyse** and **execute** is the TOCTOU window: two threads can both pass analysis, then both mutate shared catalog state.

**Analyser check without an execute-time lock = hint.**  
**Execute lock + check + act = guarantee.**

---

## Rule by phase

| Phase | Locks? | Why |
|-------|--------|-----|
| Lex / parse | No | No shared mutable state |
| Plan | No | Transforms analysed query into a plan object |
| Analyser | Optional short read | Fast “obvious” errors; not authoritative |
| **Executor** | **Yes — primary place** | Actual catalog writes and (later) row I/O |

---

## DDL (CREATE/DROP DATABASE/TABLE)

### Where to lock

- **Primary:** `CommandExecutor` (or a thin wrapper it calls).
- **Phase 1 alternative:** one catalog DDL lock in `DefaultQueryProcessor` wrapping **analyse → execute** for all DDL plans. Coarse but removes the TOCTOU gap entirely.

### Pattern A — lock at execute, re-check (target shape)

```
acquire catalog / table exclusive lock
  re-check (table exists? database empty? …)
  mutate catalog + persist to disk
release lock
```

The analyser still runs first so clients often get a clear error before waiting on a lock. The executor **must repeat** the same existence checks inside the lock.

```
Thread A                          Thread B
analyse: "users missing" ✓        analyse: "users missing" ✓
execute: acquire X                  execute: acquire X (waits)
  re-check: still missing ✓
  createTable
  release
                                  acquire X
                                  re-check: exists ✗ → ERROR
```

### Pattern B — Phase 1 shortcut

One DDL mutex around the whole `analyse → plan → execute` path for resolved DDL plans. Analyser reads are then covered by the same lock. Acceptable until `LockManager` grows table/database scopes.

### Do not

Lock only in the analyser and release before execute—another thread can change catalog between the two steps (current bug).

---

## DML (SELECT / INSERT / UPDATE / DELETE — not implemented yet)

Split **schema** (catalog) from **data** (pages/rows).

### Schema

- **Analyser:** read `TableMetadata` to resolve column names and types (optimistic, no row lock).
- **Executor:** acquire **table lock**, then scan or write rows using that schema.

If the table is dropped between analyse and execute, execute fails under the lock—that is correct.

### Row data

- **Analyser:** no row locks (does not touch pages).
- **Executor:**
  - `SELECT` → table **shared (S)** for the whole scan
  - `INSERT` / `UPDATE` / `DELETE` → table **exclusive (X)** at first (row locks later if needed)

```
SELECT shop.users:
  analyse: load column list (optional, no row lock)
  execute: acquire table S → scan pages → release S

DROP TABLE shop.users while SELECT is running:
  execute(DROP): wait until SELECT releases S → acquire X → drop
```

For DML, locks are taken **only at execute** and held for the duration of the physical read/write.

---

## DDL vs DML on the same table

When `SELECT` holds table **S** and `DROP TABLE` wants **X**:

- Both acquire locks at **execute**.
- `DROP` **waits** until active readers release **S**.
- Once `DROP` is waiting, **block new readers** on that table so DDL does not wait forever (standard metadata-lock behaviour).

The analyser does not participate in that wait queue.

**Default rule:** do not allow DDL on a table while row reads are in progress—wait or return “table in use”, do not overlap.

| Situation | Allow? |
|-----------|--------|
| `SELECT shop.users` + `SELECT shop.users` | Yes (both S) |
| `SELECT shop.users` + `INSERT shop.users` | No (writer needs X) |
| `SELECT shop.users` + `DROP TABLE shop.users` | No — DROP waits for SELECT |
| `SELECT shop.users` + `CREATE TABLE shop.orders` | Yes (different table) |
| `SELECT shop.users` + `DROP DATABASE shop` | No — database exclusive blocks all table ops in `shop` |

Lock scope hierarchy: **database → table → row**. Acquire coarse before fine; same order every time to reduce deadlock risk.

---

## Where in code (conceptually)

`DefaultQueryProcessor.execute`:

1. lex / parse — no lock
2. `analyser.analyse(ast)` — read-only catalog access today
3. `planner.plan(analyzed)` — no lock
4. `queryDispatcher().execute(plan)` — **lock here** (or wrap steps 2–4 for Phase 1 DDL)

`CommandExecutor.execute`:

1. acquire DDL lock (via future `LockManager`)
2. re-check catalog invariants
3. `catalogManager.createTable` / `dropTable` / …
4. release lock

---

## Cheat sheet

| Statement | Analyser | Executor |
|-----------|----------|----------|
| CREATE TABLE | read catalog (existence checks) | **X lock → re-check → write catalog** |
| DROP TABLE | read catalog | **X lock → re-check → delete** |
| CREATE/DROP DATABASE | read catalog | **DB X lock → re-check → mutate** |
| SELECT | read schema | **S lock → read rows → release** |
| INSERT / UPDATE / DELETE | read schema | **X lock → write rows → release** |

---

## What we are not doing yet

- Row/page locks (`BufferPool`, `TableStore` not built)
- `LockManager` / `TransactionManager` — interfaces only; not wired
- Online DDL (reads during `ALTER`) — defer until MVCC or copy-on-write schema
- Locking in lex, parse, or plan

---

## Related

- `docs/concurrency/used.md` — threads and pools today
- `docs/concurrency/todo` — catalog races to fix
- `storage/lock/LockManager.java` — stub; table-level first, row-level later
