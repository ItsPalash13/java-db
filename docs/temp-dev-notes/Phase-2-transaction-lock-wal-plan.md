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

## Build order (five steps)

Steps 1–4 are runtime. **Step 5** is a PlantUML visibility pass on `docs/lld/java-database-lld.puml` (colors + line strokes per subsystem) — do it after Step 4 so the diagram matches the finished wiring.

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

Step 5 — LLD PlantUML color pass
[ ] Stereotypes + skinparams for txn / lock / wal / explicit / catalog
[ ] Recolor association lines that belong to each subsystem (legend in `.puml` header)
[ ] Preview `.puml`; keep `.md` / `.txt` LLD in sync if stereotypes are documented there
```

---

## Step 5 — Color the PlantUML LLD by Phase 2 subsystem

**Goal:** Make `docs/lld/java-database-lld.puml` readable at a glance: each Phase 2 piece (and the catalog it mutates) uses a **distinct fill** on types and a **matching stroke** on the arrows that belong to that piece. Default interface/concrete skinparams stay for everything else (query/network/buffer).

**Why:** The diagram is dense; gray arrows and uniform amber/blue boxes hide the Step 1→4 wiring (`CommandExecutor` → txn / lock / WAL, `StorageEngine` owns, recovery edges). Color is documentation, not a code change.

**Legend (use these hexes; do not reuse across layers):**

| Subsystem | Phase step | Stereotype (suggested) | Type fill / border | Line color | What gets that color |
|-----------|------------|------------------------|--------------------|------------|----------------------|
| **Transaction** | Step 1 + orchestrator | `<<txn>>` | fill `#ccfbf1` / border `#0d9488` (teal) | `#0d9488` | `TransactionManager`, `DefaultTransactionManager`, `TransactionContext`, `TransactionControlExecutor`; edges: `StorageEngine`/`DefaultStorageEngine` → txn, `CommandExecutor` → txn, `VolcanoExecutor` → txn, `TransactionControlExecutor` → txn, `CheckpointExecutor` → txn |
| **Lock** | Step 2 | `<<lock>>` | fill `#ffedd5` / border `#ea580c` (orange) | `#ea580c` | `LockManager`, `DefaultLockManager`, `LockException`, `CatalogLockException`, `TransactionAbortedException`; edges: engine owns lock, `CommandExecutor` / `VolcanoExecutor` / `CheckpointExecutor` / `CheckpointScheduler` → lock |
| **WAL** | Step 3 | `<<wal>>` | fill `#fce7f3` / border `#db2777` (pink) | `#db2777` | `WALManager`, `DefaultWALManager`, `WalRecord`, `WalOp`, checkpoint strategy types tied to WAL flush; edges: engine owns WAL, `CommandExecutor` → WAL, `DefaultTransactionManager` → WAL, `DefaultWALManager` → `PhysicalStorage` / replay → `CatalogManager`, `CheckpointExecutor` / `CheckpointScheduler` → WAL |
| **Explicit txn control** | Step 4 | `<<explicit-txn>>` | fill `#e0e7ff` / border `#4f46e5` (indigo) | `#4f46e5` | Plans/AST/tokens only used for `BEGIN`/`COMMIT`/`ROLLBACK` if shown separately; prefer thickening or tagging the **control** edges (`TransactionControlExecutor` → `TransactionManager` begin/commit/rollback) so multi-statement path stands out from implicit `runInTransaction` (teal) |
| **Catalog (touched by Phase 2)** | Phase 1 surface | `<<catalog>>` | fill `#ecfdf5` / border `#059669` (green) | `#059669` | `CatalogManager`, `DefaultCatalogManager`, `CatalogStore`, `JsonCatalogStore`, catalog metadata types on the DDL write path; edges: `CommandExecutor` → catalog writes, analyser **reads** can stay default gray or a lighter green dashed |

**How to edit `.puml`:**

1. Keep existing global `skinparam interface` / `<<concrete>>` / enum / exception as the **baseline**.
2. Add stereotypes on Phase 2 types, e.g. `interface TransactionManager <<txn>>`, `class DefaultLockManager <<concrete>> <<lock>>`, then:

```plantuml
skinparam class<<txn>> {
  BackgroundColor #ccfbf1
  BorderColor #0d9488
}
skinparam interface<<txn>> {
  BackgroundColor #ccfbf1
  BorderColor #0d9488
}
' same pattern for <<lock>>, <<wal>>, <<catalog>>, <<explicit-txn>>
```

3. Recolor **only** the association lines that carry that subsystem’s protocol. PlantUML: put the color after the arrow:

```plantuml
CommandExecutor --> TransactionManager #0d9488 : implicit txn / explicit append
CommandExecutor --> LockManager #ea580c : table X / database X / catalog X
CommandExecutor --> WALManager #db2777 : append (flush at commit)
DefaultTransactionManager --> WALManager #db2777 : flush COMMIT / discard
```

4. Put a short **color legend comment** at the top of `java-database-lld.puml` (under the file header) so the legend survives regenerations and matches this table.
5. Same change set: if stereotypes appear in the diagram, note them in `java-database-lld.md` / `.txt` only if those docs list stereotypes; do not invent new types.

**Not in this step:** rearranging packages, deleting edges, or changing Java. Parallel checklist items (eager dispatcher, graceful shutdown) stay separate.

**Done when:** opening the `.puml` preview, you can trace teal = txn orchestration, orange = locks, pink = WAL durability, green = catalog mutate/replay target, without reading every label.

---

## One line

**TransactionManager first as orchestrator (implicit commit) → LockManager for threads → WAL for crashes → BEGIN/COMMIT last when commit means flush + release → color the `.puml` by subsystem so those edges stay visible.**
