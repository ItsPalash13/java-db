# DDL with LockManager, TransactionManager, and WAL

Notes on how real databases use WAL and transactions for DDL, and what to build in this project now vs when DML arrives.

Related: `Phase-2-transaction-lock-wal-plan.md`, `DDL-and-concurrency-plan.md`, `docs/implementations/lock-timing.md`, `docs/concurrency/todo`.

---

## What each piece does for DDL

| Piece | Role for DDL |
|-------|----------------|
| **LockManager** | Stops other sessions from using/changing the same schema while DDL runs |
| **TransactionManager** | Defines a unit of work: commit = visible + durable; rollback = undo |
| **WALManager** | Writes **intent** to disk **before** catalog/data files change; replay after crash |

- **Concurrency (threads)** → mostly **LockManager**
- **Crash recovery** → **WAL**
- **All-or-nothing across steps** → **TransactionManager**

---

## How real DBs use WAL for DDL

Typical pattern:

```text
1. Acquire metadata / DDL locks
2. Append WAL record: "CREATE TABLE shop.users (...)" or catalog delta
3. Apply change (memory + catalog file / system tables)
4. Flush WAL
5. Release locks
6. (Maybe) fsync data files later
```

**Why WAL for DDL:** if the process dies after applying memory/disk but before everything is consistent, restart **replays WAL** and finishes or rolls back — not only in-memory rollback on one thread.

**What gets logged (conceptually):**

- create/drop database
- create/drop table
- add/drop column
- create/drop index definition

Not row inserts yet — but the **same WAL pipeline** later logs `INSERT` into pages.

For this project: a small **catalog WAL entry** (JSON or typed record) before `catalog.json` write is enough to prepare for DML.

---

## How real DBs use transactions for DDL

**Varies by engine.**

### PostgreSQL (good mental model)

- DDL inside `BEGIN … COMMIT` is **transactional** (mostly)
- `ROLLBACK` can undo `CREATE TABLE` if nothing committed yet
- Uses **ACCESS EXCLUSIVE** (or similar) locks so nobody else touches that object during DDL
- Catalog changes go through system catalogs + WAL like any other change

### MySQL / InnoDB

- Historically: **DDL often implicit COMMIT** — even inside `BEGIN`, `CREATE TABLE` commits what came before
- Newer **atomic DDL** for some ops: one DDL = one internal transaction + redo
- **Metadata locks (MDL)** separate from row locks — DDL waits for open reads

### SQLite (simple)

- Schema in `sqlite_master`
- DDL is **automatically transactional** with the schema change logged in the journal/WAL

**Common pattern:**

1. Metadata lock
2. Log the schema change
3. Apply catalog
4. Commit (or implicit commit for one statement)

---

## DDL vs DML: same WAL, different records

```text
WAL stream (one file / chain)
├── DDL:  CREATE TABLE shop.users (columns…)
├── DDL:  ADD COLUMN shop.users.age INT
├── DML:  INSERT shop.users row (page 3, slot 1)   ← later
└── DML:  UPDATE shop.users …                      ← later
```

One **WALManager**, different **record types**. DML adds page/row redo; DDL adds catalog redo. Recovery replays in order.

---

## TransactionManager + DDL in this project

### Single-statement DDL (today)

```text
implicit transaction {
  lock catalog
  re-check
  append WAL
  apply catalog + flush file
  commit   // WAL fsync'd; locks released
}
```

No client `BEGIN` needed yet — each `CREATE TABLE` is one transaction.

### Multi-statement later

```sql
BEGIN;
CREATE TABLE shop.a (...);
CREATE TABLE shop.b (...);
COMMIT;   -- both or neither
```

TransactionManager then:

- holds **catalog lock** (or finer locks) until `COMMIT` / `ROLLBACK`
- buffers multiple WAL records
- on `ROLLBACK`, undo in-memory + uncommitted WAL tail is not replayed on recovery

**Plan:** implicit one-shot transactions per DDL now; design `TransactionManager` so DML can add explicit `BEGIN` / `COMMIT` later.

---

## Locks + WAL + transactions together (CREATE TABLE)

```text
Client: CREATE TABLE shop.users (...)

TransactionManager  begin (implicit)
LockManager         catalog exclusive
WALManager          log CreateTable{shop, users, columns}
CatalogManager      memory + catalog.json + flush
WALManager          flush WAL
TransactionManager  commit
LockManager         release
```

### Crash points

| Crash after | On restart |
|-------------|------------|
| WAL logged, catalog not written | Replay WAL → create table |
| Catalog written, WAL not flushed | Policy-dependent; real DBs flush WAL before commit |
| Memory rollback only (today, no WAL) | Other threads may have seen half state; disk may be partial |

---

## What to build now vs with DML

### Now (DDL + prepare for DML)

- **LockManager** — catalog exclusive at execute (`runExclusiveCatalog(Runnable)`, blocking)
- **WALManager** — append **catalog change records** before persist; flush before commit
- **TransactionManager** — minimal: `runInTransaction(Runnable)` for one DDL statement (implicit commit)

### With DML

- Table **shared / exclusive** locks
- WAL **page** records
- Explicit **BEGIN / COMMIT / ROLLBACK**
- Locks held for transaction duration where needed

You do **not** need row WAL or long transactions to **start** logging DDL — that is a valid preparation step.

---

## Design choices to decide before coding

- **WAL record shape:** one JSON line per DDL vs typed binary records
- **Commit rule:** commit = WAL flushed, then catalog file flushed (order matters)
- **Recovery:** replay WAL on `StorageEngine.start()` before or alongside `catalogManager.load()`
- **DDL in user transactions:** defer explicit `BEGIN` until DML; implicit commit per statement now
- **Rollback:** in-memory undo + uncommitted WAL records not replayed after rollback

---

## LockManager API (Phase 1 DDL)

- **Scope:** catalog exclusive first (`catalogLock` inside `DefaultLockManager`, one per `StorageEngine`)
- **Style:** `runExclusiveCatalog(Runnable)` — lock/unlock in `finally`
- **Wait:** blocking (timeout/error optional later)
- **Call site:** `CommandExecutor` at execute; analyser stays lock-free; re-check inside lock

---

## One line

Real DBs: **lock schema → log DDL to WAL → apply catalog → commit (WAL durable)**. Transactions group steps and define visibility; DML reuses the same WAL and transaction path with table/row locks. Here: **catalog WAL + implicit per-statement transactions now**; extend to DML records and `BEGIN` / `COMMIT` when `TableStore` exists.
