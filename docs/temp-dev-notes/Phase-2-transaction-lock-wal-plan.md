# Phase 2 plan — TransactionManager, LockManager, WAL

Implementation plan after **Phase 1 DDL is complete** (all catalog statements execute and survive restart).

**Strategy:** `TransactionManager` is the **orchestrator** first (implicit single-statement only). Plug in **LockManager** then **WALManager** before client-visible `BEGIN` / `COMMIT` / `ROLLBACK`. Multi-statement sessions come last.

Related:

- `DDL-and-concurrency-plan.md` — Phase 1 + quick concurrency fixes (superseded for Phase 2 by this doc)
- `DDL-WAL-transactions.md` — how real DBs use WAL/transactions for DDL
- `docs/implementations/lock-timing.md` — locks at execute, analyser lock-free
- `docs/concurrency/todo` — unsafe list

---

## Where we are

Phase 1 DDL done:

- CREATE/DROP DATABASE, CREATE/DROP TABLE
- ALTER ADD/DROP COLUMN, CREATE/DROP INDEX (catalog definitions)
- Qualified `db.table`, per-table `catalog.json`
- Pipeline: lex → parse → analyse → plan → `QueryDispatcher` → `CommandExecutor` → `CatalogManager`

Still empty / unsafe:

- `TransactionManager`, `LockManager`, `WALManager` — interfaces only
- Shared catalog with no locks (see `docs/concurrency/todo`)
- Lazy `queryDispatcher`, graceful shutdown — still open (Steps 6–7 below)

---

## What each piece does (do not confuse)

| Piece | Fixes | Does not fix |
|-------|--------|--------------|
| **TransactionManager** | Groups work; commit vs rollback semantics | Thread races alone; crash after kill |
| **LockManager** | Concurrent threads on catalog / tables | Crash recovery |
| **WALManager** | Crash recovery; durable intent before catalog files | Two threads at once |

**Commit path (target):**

```text
begin → lock → append WAL → apply catalog → flush WAL → commit → unlock
```

TransactionManager **calls** lock + WAL; it does not replace them.

---

## Build order (four steps)

### Step 1 — TransactionManager shell (implicit single-statement)

**Goal:** Every DDL runs inside a transaction API; no client `BEGIN` yet.

**API (sketch):**

```java
// One DDL statement = one implicit transaction
transactionManager.runInTransaction(() -> {
    // lock + catalog work added in Step 2–3
});
```

**Work:**

- `DefaultTransactionManager` on `StorageEngine`
- `runInTransaction(Runnable)` / `runInTransaction(Supplier<T>)`
- On success: `commit()` (no-op until WAL wired)
- On exception: `rollback()` (in-memory only until WAL wired)
- `CommandExecutor` (or thin wrapper) always enters through `runInTransaction`
- Unit tests: success commits path; failure rolls back without leaving catalog dirty **on that thread**

**Not yet:**

- Parser support for `BEGIN` / `COMMIT` / `ROLLBACK`
- Per-connection `TransactionContext`
- Multi-statement batching

**Done when:** all DDL goes through `TransactionManager`; behaviour unchanged except structured commit/rollback hooks exist.

---

### Step 2 — LockManager (catalog exclusive)

**Goal:** Fix catalog races from `docs/concurrency/todo` (TOCTOU, maps, `nextTableId`, non-atomic rollback visibility).

**API (sketch):**

```java
lockManager.runExclusiveCatalog(() -> { ... });  // blocking; ReentrantLock inside
```

**Work:**

- `DefaultLockManager` on `StorageEngine`; one `catalogLock` per engine
- Called from inside `runInTransaction` (order: begin → **lock** → … → unlock → commit)
- Re-check catalog invariants under lock (in `CommandExecutor` or `CatalogManager` methods only called under lock)
- Analyser stays lock-free
- Multi-threaded tests: two `CREATE TABLE` same name → one OK, one ERROR; no duplicate `tableId`

**Scope:** catalog exclusive only. No table S/X until DML.

**Done when:** concurrent DDL from two TCP clients is safe.

---

### Step 3 — WALManager (catalog records + replay)

**Goal:** Crash-safe DDL; same pipeline ready for DML page records later.

**Work:**

- `DefaultWALManager` — append-only log under `data/` (e.g. `wal.log` or segmented files)
- **Record types (Phase 2a):** catalog DDL only (`CREATE_TABLE`, `DROP_TABLE`, `ADD_COLUMN`, …)
- **Order inside transaction:**
  1. append WAL record
  2. apply `CatalogManager` + persist `catalog.json`
  3. `flush()` WAL on **commit**
- **Recovery:** `StorageEngine.start()` → `walManager.replay()` before or merged with `catalogManager.load()`
- Test: create table → kill before catalog flush → restart → replay restores table (see `DDL-WAL-transactions.md`)

**Decisions to lock in:**

| Topic | Recommendation |
|-------|----------------|
| Record format | JSON lines per record (simple) → binary later |
| Commit rule | WAL flushed before transaction marks committed |
| Uncommitted WAL | Not replayed after rollback |

**Not yet:** DML page/row WAL records.

**Done when:** committed DDL survives simulated crash; replay idempotent.

---

### Step 4 — Explicit transactions (multi-statement) ✅

**Goal:** `BEGIN; …; COMMIT` / `ROLLBACK` on one connection.

**Work:**

- Per-connection `TransactionContext` (ThreadLocal on `db-conn-*` thread)
- Parser: `BEGIN`, `COMMIT`, `ROLLBACK` (+ optional `TRANSACTION`)
- WAL: `txnId` on every DDL line; `COMMIT(txnId)` line; flush once at commit
- Replay: apply DDL only when matching `COMMIT(txnId)` seen; legacy lines without txnId still replay
- While in explicit txn: hold catalog lock; defer `catalog.json` until `COMMIT`; snapshot restore on `ROLLBACK`
- Implicit single statement: internal begin → one DDL → COMMIT → flush (auto-commit)
- Rules: second `BEGIN` while in txn → error; after `ROLLBACK`, next bare DDL auto-commits

**Done when:** `BEGIN; CREATE TABLE shop.a (...); CREATE TABLE shop.b (...); COMMIT` all-or-nothing; `ROLLBACK` leaves neither table.

---

## Small fixes (anytime in parallel with Step 1–2)

From original concurrency plan — not blocked on WAL:

| Fix | One liner |
|-----|-----------|
| **Eager QueryDispatcher** | Build after `StorageEngine.start()`, not lazy on first DDL |
| **Graceful shutdown** | Stop accept → wait for workers → avoid `shutdownNow` mid catalog write |

---

## Wiring (target)

```text
StorageEngine
 ├── DefaultTransactionManager
 ├── DefaultLockManager
 ├── DefaultWALManager
 └── DefaultCatalogManager

CommandExecutor(transactionManager, lockManager, catalogManager)
  → transactionManager.runInTransaction(() ->
       lockManager.runExclusiveCatalog(() ->
         wal.append(ddlRecord)   // Step 3
         catalogManager.createTable(...)
       ))
```

Later: `TransactionManager` receives `LockManager` + `WALManager` in constructor; `CommandExecutor` only talks to `TransactionManager` if txn layer wraps lock+WAL internally (pick one façade — prefer txn orchestrates).

---

## Lock scope roadmap (after Step 2)

| Stage | Scope |
|-------|--------|
| **Now (Step 2)** | Catalog exclusive — all DDL serialized |
| **DML** | Table shared (SELECT) / exclusive (writes, DDL on table) |
| **Later** | Row/page locks |

See `docs/implementations/lock-timing.md`.

---

## What “done” does not mean yet

- SELECT / INSERT / UPDATE / DELETE execution
- `TableStore`, `BufferPool`, physical index trees
- Table-level locks while only DDL exists (optional refinement before DML)
- Lock timeout / `ERROR: busy` (optional)
- Cross-process file locking on `data/` (single JVM assumed)

---

## Checklist

```text
Step 1 — TransactionManager shell
[x] DefaultTransactionManager on StorageEngine
[x] runInTransaction around all CommandExecutor DDL
[x] Tests: success / failure / nested rejected; CommandExecutor still OK

Step 2 — LockManager
[x] DefaultLockManager.runExclusiveCatalog
[x] Re-check invariants under lock (CatalogManager methods called only under lock)
[x] Multi-threaded DDL tests
[x] LLD update

Step 3 — WALManager
[x] Append catalog DDL records before catalog persist
[x] Flush WAL on commit (and before catalog apply)
[x] Replay on StorageEngine.start()
[x] Crash/restart test
[x] LLD update

Step 4 — Explicit BEGIN/COMMIT/ROLLBACK
[x] TransactionContext per connection (ThreadLocal)
[x] txnId + COMMIT in WAL; replay commit-gated
[x] Parser + processor routing for txn commands
[x] Multi-statement integration test
[x] LLD update

Parallel
[ ] Eager QueryDispatcher
[ ] Graceful TcpNetworkModule shutdown
```

---

## One line

**TransactionManager first as orchestrator (implicit commit) → LockManager for threads → WAL for crashes → BEGIN/COMMIT last when commit means flush + release.**
