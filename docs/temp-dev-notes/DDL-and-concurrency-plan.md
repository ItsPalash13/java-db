# Work plan — finish Phase 1 DDL, then concurrency

Snapshot of where the project stood for Phase 1 DDL and quick concurrency fixes.

**Phase 1 DDL is complete.** For TransactionManager / LockManager / WAL / explicit `BEGIN`/`COMMIT`, use **`Phase-2-transaction-lock-wal-plan.md`**.

Phase 2 (`LockManager`, `TransactionManager`, `WALManager`, DML row storage) is planned in that doc.

---

## Decision

1. **Finish remaining Phase 1 DDL** (catalog-only: ALTER ADD COLUMN, INDEX definitions).
2. **Then concurrency hardening** for the catalog path (three small fixes — not full Phase 2).
3. **Then** DML / WAL / real `LockManager` when `TableStore` exists.

While finishing DDL: treat the server as **one DDL client at a time** in manual tests. The execution path is still single-threaded-safe by convention, not by locks.

---

## Status vs `DDL-Phase-1.md`

The “What is already done” table at the top of `DDL-Phase-1.md` is **stale**. Actual status:

| Sub-phase | Goal | Status |
|-----------|------|--------|
| 1.1 | In-memory catalog | Done |
| 1.2 | Minimum `PhysicalStorage` | Done |
| 1.3 | `CatalogStore` + load on start | Done |
| 1.4 | Typed `CREATE TABLE` parse | Done |
| 1.5 | Analyser for CREATE TABLE | Done |
| 1.6 | Planner for CREATE TABLE | Done |
| 1.7 | Command execution path | Done (`QueryDispatcher`, `CommandExecutor`) |
| 1.8 | CREATE TABLE + restart | Done (restart tests) |
| 1.9 | DROP TABLE | Done |
| — | CREATE/DROP DATABASE, `db.table`, per-table `catalog.json` | Done (beyond original doc) |
| **1.10** | ALTER TABLE ADD COLUMN (catalog-only) | **Not done** — parsed only, echoes `OK` |
| **1.11** | CREATE/DROP INDEX (definitions on `TableMetadata`) | **Not done** — parsed only, echoes `OK` |

Each remaining statement follows the same slice as 1.5–1.7: **analyse (read) → plan → `CommandExecutor` (write) → `CatalogStore`**. No new storage layers.

---

## Step 1 — Confirm 1.8 still green

Quick sanity before new DDL:

- `CREATE TABLE shop.users (...)` persists and reloads after restart.
- Duplicate `CREATE TABLE` returns an error, not `OK`.
- Existing restart / processor tests pass.

---

## Step 2 — 1.10 ALTER TABLE ADD COLUMN

See `Alter-Column.md`. Catalog-only; no row rewrite (no rows yet).

**Work:**

- Parser: ADD requires a type (`ADD age INT`), matching typed CREATE TABLE.
- Analyser: table exists, column name new, type known.
- Plan + `QueryType` + `CommandExecutor` branch.
- `CatalogManager` / `CatalogStore`: append column to `TableMetadata`, persist JSON.
- Tests: ADD persists, restart shows column, duplicate ADD fails.

**Optional in same sub-phase:** DROP COLUMN as catalog-only if equally small; prefer ADD first.

**Not in 1.10:** `NOT NULL`, `DEFAULT`, row rewrite, locks.

---

## Step 3 — 1.11 CREATE INDEX / DROP INDEX (definitions only)

**Work:**

- Store index definitions on `TableMetadata` (name, column ids; not unique enforcement).
- Analyser: table exists, columns exist, index name not duplicated.
- Plan + execute + catalog JSON round-trip.
- Tests: definition survives restart; duplicate CREATE INDEX fails.

**Not in 1.11:** `IndexStore` trees, extra files, index-backed lookups.

---

## Step 4 — Concurrency hardening (before DML)

Full Phase 2 is not required yet. Three fixes from `docs/concurrency/todo`:

### 4a — Catalog DDL lock (one fix for most catalog races)

**Problem:** shared `DefaultCatalogManager`, TOCTOU, non-atomic rollback, map/`nextTableId` races, concurrent catalog file writes from two threads.

**Fix:** exclusive catalog lock at **execute** in `CommandExecutor` / `DefaultCatalogManager`:

```text
lock
  re-check invariants (tableExists, databaseExists, …)
  mutate memory
  persist (+ rollback on failure, still inside lock)
unlock
```

**Design:** see `docs/implementations/lock-timing.md`.

- Analyser stays lock-free (optimistic errors only).
- Executor re-checks under lock; no “retry whole query” for catalog conflicts — return `ERROR`.
- Phase 1 can use `ReentrantLock` on catalog before wiring full `LockManager`.

### 4b — Eager `QueryDispatcher` init

**Problem:** lazy `queryDispatcher()` — unsynchronized null check, not `volatile`.

**Fix:** build dispatcher once after `StorageEngine.start()` (in `DatabaseServer.start()` or processor hook), not on first DDL query.

### 4c — Graceful shutdown

**Problem:** `workers.shutdownNow()` can interrupt mid catalog write.

**Fix:** stop accepting connections, `shutdown()` + wait for in-flight queries to finish; avoid interrupt during catalog critical section.

---

## Step 5 — Phase 2 (later)

Not part of this plan’s immediate work:

- `LockManager` table/database scopes, S/X for DML
- `TransactionManager`, `WALManager`
- `TableStore`, `BufferPool`, `IndexStore` physical structures
- SELECT / INSERT / UPDATE / DELETE execution

---

## Suggested order (checklist)

```text
[ ] Confirm 1.8 green
[ ] 1.10 ALTER ADD COLUMN (execute path + tests + LLD)
[ ] 1.11 INDEX definitions (execute path + tests + LLD)
[ ] 4a Catalog DDL lock at execute + re-check
[ ] 4b Eager QueryDispatcher after start
[ ] 4c Graceful network shutdown
[ ] Update DDL-Phase-1.md “What is already done” table (optional cleanup)
```

---

## Related docs

| Doc | Purpose |
|-----|---------|
| `Phase-2-transaction-lock-wal-plan.md` | **Current** — txn → lock → WAL → BEGIN/COMMIT |
| `DDL-WAL-transactions.md` | Background: real DBs, replay, roles |
| `DDL-Phase-1.md` | Original sub-phase map (1.1–1.11) |
| `DDL_phases.md` | Phase 1 vs Phase 2 boundary |
| `Alter-Column.md` | ADD COLUMN rules |
| `docs/implementations/lock-timing.md` | Where locks go (analyser vs execute) |
| `docs/concurrency/used.md` | Threads today |
| `docs/concurrency/todo` | Unsafe list + fix grouping |
