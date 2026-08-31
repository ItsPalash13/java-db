# Lock scopes

What object a lock names (database, table, row). Not the same as **mode** (S vs X) or **duration** (statement vs until COMMIT). Those three combine: “table `shop.users`, exclusive, held until COMMIT.”

Related:

- `docs/implementations/lock-timing.md` — **when** to lock (execute, not analyser)
- `docs/temp-dev-notes/Phase-2-transaction-lock-wal-plan.md` — catalog exclusive today; table S/X later
- `docs/concurrency/used.md` — one `ReentrantLock` for all catalog DDL
- `docs/todo` — scoped LockManager, whole-txn hold, deadlock detection

---

## Three knobs (do not collapse)

| Knob | Question | Example |
|------|----------|---------|
| **Scope** | Which object? | table `shop.users` |
| **Mode** | Shared or exclusive? | S for SELECT, X for INSERT |
| **Duration** | When released? | auto-commit: end of statement; explicit: COMMIT/ROLLBACK |

`BEGIN` does not introduce a new scope. After scopes exist, `BEGIN` takes **nothing**; the first `SELECT`/`INSERT` takes table (and database intention) locks and duration decides whether they stick until COMMIT.

---

## What we have now: one scope

`DefaultLockManager` is a single engine-wide **catalog** lock. Every implicit DDL and every `BEGIN` takes it (`lockExclusiveCatalog` / `runExclusiveCatalog`). Two connections cannot `SELECT shop.users` and `CREATE TABLE shop.orders` at once — there is no “users” vs “orders”, only “the catalog.”

That was correct while the only shared mutable state was catalog JSON. DML adds **row files per table**, so the lock name must be at least as fine as the data.

`BEGIN` today is the **wrong scope**: it holds catalog X for the whole session. Target: `BEGIN` is empty; `BEGIN; SELECT shop.users` takes table S (and db IS) and **keeps them until COMMIT** (duration, same scopes).

---

## Hierarchy (coarse → fine)

```text
engine / catalog     DROP DATABASE names, CHECKPOINT, nextTableId
  database  shop     everything in that database
    table   users    rows + indexes of that table
      row   id=1     one record (later)
```

**Acquire order:** coarse before fine, always. Never take a row lock then the table lock. Same order for every session keeps deadlock rare until row locks or SQL-order multi-table txns.

**Intention (why database locks exist):** if session A holds `shop.users` S, session B’s `DROP DATABASE shop` must wait. B cannot see every table lock unless A also left a mark on **database `shop`**. That mark is an **intention** lock:

| You want | Also take on the parent |
|----------|-------------------------|
| table S | database **IS** (intent shared) |
| table X | database **IX** (intent exclusive) |
| DROP DATABASE | database **X** (conflicts with IS and IX) |

Without IS/IX, `DROP DATABASE` has nothing to wait on except “scan all table locks” or “take the old catalog mutex again.” For this project, **table S/X + database IS/IX/X** is the real scoped model. Skip row locks until table X is too coarse.

Do **not** put page or B+Tree node in this hierarchy. Those are **latches** (short, in BufferPool / tree code) so two threads do not tear a page. They are not SQL locks and they must not be held until COMMIT.

---

## Mode vs scope

Same object, two user-visible modes:

- **S** — many readers
- **X** — one writer; no readers

Intention on the parent is not a third user-visible lock type. It only exists so parent X can conflict with children.

```text
SELECT users     →  db shop IS  +  table users S
INSERT users     →  db shop IX  +  table users X
DROP TABLE users →  db shop IX  +  table users X
DROP DATABASE    →  db shop X
```

Compatibility:

- S+S on the same table → yes
- S+X or X+X on the same table → wait
- `shop.users` S and `shop.orders` X → yes (different tables; both only IS/IX on `shop`)

---

## Statement map

| Statement | Scope + mode | Why |
|-----------|----------------|-----|
| `SELECT … FROM shop.users` | `shop` IS + `users` S | Concurrent readers; block writers/DDL on `users` |
| `INSERT` / `UPDATE` / `DELETE` `shop.users` | `shop` IX + `users` X | Whole-table X until row locks |
| `CREATE TABLE shop.users` | `shop` IX + `users` X (name reservation) | Two sessions must not create the same name; other tables stay free |
| `DROP TABLE` / `ALTER ADD\|DROP COLUMN` / `CREATE\|DROP INDEX` | `shop` IX + `users` X | Schema + later heap/index files for that table |
| `CREATE DATABASE shop` | catalog/database-name X for `shop` | No tables yet |
| `DROP DATABASE shop` | `shop` X | Must wait for every IS/IX on `shop` |
| `BEGIN` | **nothing** | Session starts empty; first statement takes the locks above |
| `COMMIT` / `ROLLBACK` | release all held | Duration, not a new scope |
| `CHECKPOINT` | keep a **global** exclusive (catalog or engine) | Must not race catalog persist / WAL fence; not a table lock |
| `DESCRIBE` / `SHOW` | optional: catalog read or table IS+S | Read-only metadata; can stay lock-free at first if SHOW may be stale |

Same matrix as `lock-timing.md` for DML vs DDL on one table:

| Situation | Allow? |
|-----------|--------|
| `SELECT shop.users` + `SELECT shop.users` | Yes (both S) |
| `SELECT shop.users` + `INSERT shop.users` | No (writer needs X) |
| `SELECT shop.users` + `DROP TABLE shop.users` | No — DROP waits for SELECT |
| `SELECT shop.users` + `CREATE TABLE shop.orders` | Yes (different table) |
| `SELECT shop.users` + `DROP DATABASE shop` | No — database X vs IS |

---

## What not to make a lock scope

- **Lexer / parser / planner** — no shared mutable storage.
- **Analyser** — optimistic catalog read; execute re-checks under the lock (`lock-timing.md`).
- **WAL file** — serialize appends inside `WALManager` (mutex/latch), not a user lock named `wal.log`.
- **Index** as a separate user lock — `CREATE INDEX` and `SELECT` using that index still lock the **table**. The tree uses page latches internally.
- **Column** — `ALTER … DROP COLUMN` is table X. Column-level locks are unused at this SQL surface.
- **Page** in `LockManager` — BufferPool pin/latch, milliseconds, never until COMMIT.

---

## Catalog vs table: do not collapse them

Catalog JSON is still shared. After scopes:

- Mutating `TableMetadata` for `shop.users` happens **while holding `users` X**, then persist that table’s `catalog.json`.
- `nextTableId` / database directory create still needs a **narrow catalog or database lock**, not “lock every table.”
- Retire `runExclusiveCatalog()` for DML and table DDL. Keep a small catalog lock only for id allocation, `CREATE/DROP DATABASE`, and CHECKPOINT — or fold those into database/catalog scopes.

Do not hold catalog X for `SELECT`. That would make scopes useless.

---

## Two granularities

**Table X for all writes (first DML).** One writer per table. Dummy Volcano and file `TableStore` both work. Concurrent `SELECT`+`SELECT` yes; `SELECT`+`INSERT` no.

**Row X later.** `UPDATE … WHERE id = 1` only blocks that row; another session can `UPDATE id = 2`. Then you need:

- row scope under the table
- usually **table IX** (not table X) so two row-X can coexist
- deadlock detection (two txns, two rows, opposite order)

Deadlock detection is **required** at row scope. At table-only scope it still matters for **two tables in one txn** (`users` then `orders` vs the reverse). Timeout (30s `CatalogLockException`) is not detection — the blocker may still be running.

---

## Duration (scopes stay the same)

| Session | Hold |
|---------|------|
| Auto-commit (`INSERT` with no `BEGIN`) | acquire table X → write → **release** |
| `BEGIN` … `COMMIT` | acquire on first use of that table, **keep until COMMIT/ROLLBACK/disconnect** |

```text
BEGIN
  INSERT shop.users VALUES (1)   -- shop IX + users X, held
  SELECT * FROM shop.orders      -- shop IS + orders S, held
COMMIT                           -- release all
```

`TransactionContext` should remember held table/database locks the same way it already remembers catalog snapshot + `txnId`. Disconnect already rolls back the explicit txn; it must also drop those locks (today `endConnectionSession` only unlocks catalog).

---

## Build order (scopes only)

| Phase | Scopes in `LockManager` |
|-------|-------------------------|
| Dummy Volcano, single-threaded | none new (today’s catalog lock still wraps DDL) |
| First concurrent DML | **table S/X** + **database IS/IX/X** |
| Explicit txn | same objects, held until COMMIT |
| Later | **row X** (+ table IX instead of table X on UPDATE/DELETE) |

Page latches arrive with BufferPool, orthogonal to this list.

**Fork if we skip intention:** table S/X for DML only, and leave `DROP DATABASE` (and maybe all DDL) on the old catalog exclusive lock. Simpler; `DROP DATABASE` still blocks the world. Without IS/IX, `DROP DATABASE` vs `SELECT` is wrong unless catalog exclusive stays for that DDL.

**Preferred:** implement IS/IX with the first table S/X so `DROP DATABASE` waits on readers in that database instead of serializing the whole engine.

---

## One line

Scope names the object (db → table → row); mode is S/X plus parent IS/IX; duration is statement vs until COMMIT. Do not use catalog X for SELECT, do not latch pages until COMMIT, and do not skip database intention if `DROP DATABASE` must wait for table readers.
