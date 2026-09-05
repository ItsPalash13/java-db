# JavaDatabase — Product Overview

**JavaDatabase** is a learning-oriented, single-node **OLTP** relational engine in Java: clients speak a SQL subset over a length-prefixed **TCP** wire protocol, and the server runs a **multi-threaded** accept/worker model (one accept thread, one connection thread per client, sequential queries per session, optional background **checkpoint** scheduler). Queries go through a classic pipeline—**lexer → parser → analyser → planner → Volcano-style pull executor**—over **page-backed heap** storage (`.ibd`), **B+ tree indexes** (`.idx`), a JSON **catalog**, and a shared **buffer pool** that uses **clock (second-chance) replacement**: only **clean, unpinned** frames are eviction candidates; **dirty frames are never stolen** (global **no-steal**—no flush-on-evict), so dirty pages stay in RAM until explicit **`flush` / `flushAll` / `CHECKPOINT`**, and the pool can exhaust under heavy DML until a checkpoint clears dirty bits. Durability sits on a **write-ahead log (WAL)** with **WAL-before-data**, **commit log flush** (no-force of data pages), logical **redo**, RAM **undo**, and **crash recovery** (catalog replay → redo committed DML → index WAL) plus checkpoint fencing. Concurrency is **Strict 2PL** with a lock hierarchy (**ENGINE → CATALOG → DATABASE → TABLE → ROW**), intention modes (**IS/IX**) and row **S/X**, **wait-for-graph deadlock detection** (abort youngest), and lock timeouts; isolation is **READ COMMITTED only** (not **MVCC**, not **REPEATABLE READ** / **SERIALIZABLE**), so non-repeatable reads, phantoms, and write skew are allowed by design while dirty reads and same-row lost updates are prevented via cascadeless post-lock re-reads. It supports **auto-commit** and explicit **BEGIN/COMMIT/ROLLBACK**, **DDL** (databases, tables, ALTER column, indexes, single-column **PRIMARY KEY** / **UNIQUE**), and **DML/DQL** (`INSERT`/`UPDATE`/`DELETE`/`SELECT` with equality/range **index scans**) aimed at educational concurrency and durability behavior rather than production throughput or full SQL completeness.

---

## What it is

- **Server + REPL** — `database-server` listens on TCP; `database-client` is an interactive client.
- **SQL subset** — DDL, DML, DQL, `BEGIN`/`COMMIT`/`ROLLBACK`, and manual `CHECKPOINT`. Names are fully qualified (`database.table`); there is no `USE`.
- **Educational scope** — visible concurrency and crash/restart behavior matter more than throughput or SQL completeness.
- **Not a full RDBMS** — no auth, pooling, prepared statements, or cost-based optimizer.

---

## Architecture

- **Query pipeline** — Lexer → Parser → Analyser (catalog names) → Planner → `QueryDispatcher` → `CommandExecutor` (DDL/checkpoint) or `VolcanoExecutor` (DML/DQL).
- **Storage** — `CatalogManager` + `FileTableStore` + `FileIndexStore` + shared `BufferPool` + `PhysicalStorage`.
- **Plans** — operators `SeqScan`, `IndexScanOperator`, `Filter`, `Project`, `Insert`, `UpdateOperator`, `DeleteOperator`. Planner uses **index scan** for equality or one-sided range (`>`, `>=`, `<`, `<=`) on a leading indexed column; if several indexes match, equality and unique/PK rank higher than range, then more prefix columns, then narrower key.
- **Wire protocol v1** — length-prefixed frames; SQL in, JSON out (`OK`, `ERROR`, `RESULT_SET`, `DONE`). See `docs/protocol/wire-protocol.md`.

---

## Features

### SQL & catalog

- **Databases & tables** — `CREATE`/`DROP DATABASE`, `CREATE`/`DROP TABLE`.
- **Schema** — `ALTER TABLE … ADD COLUMN` / `DROP COLUMN`.
- **Indexes** — `CREATE [UNIQUE] INDEX`, `DROP INDEX`; sort-build into `.idx`; leading-column equality/range uses index scan (key order); `UNIQUE` enforced on DML and build.
- **PRIMARY KEY** — single-column `PRIMARY KEY` on `CREATE TABLE`; column is `NOT NULL`; uniqueness via auto unique index `pk_<table>_<col>`.
- **Introspection** — `DESCRIBE`, `SHOW DATABASES`, `SHOW TABLES`.
- **DML / DQL** — `INSERT`, `SELECT` (projections + `WHERE`), `UPDATE`, `DELETE`.
- **Types** — `INT`, `VARCHAR`, `BOOLEAN` (nullable unless PRIMARY KEY).

### Transactions & isolation

- **Implicit** — auto-commit statements run in `runInTransaction`; write **X** locks last until commit/abort.
- **Explicit** — `BEGIN` / `COMMIT` / `ROLLBACK`. Catalog is snapshotted at `BEGIN`; DML rolls back through the **undo log**.
- **Isolation** — **`READ COMMITTED` only** (see tables below).
- **Reads** — ENGINE **IS** + table **IS** + row **S**; shared locks drop at **statement end**.
- **Writes** — ENGINE **IX** + table **IX** + row **X** until `COMMIT`/`ROLLBACK` (Strict 2PL; IX is not dropped with shared locks).
- **Row keys** — internal immutable **`rowId`**, not user columns.
- **Read-your-writes** — holding **X** does not block **S** on the same row.
- **Cascadeless reads** — after waiting for **S**, the reader re-reads the live heap row.
- **Write `WHERE`** — `UpdateOperator` / `DeleteOperator` take **X**, then `findByRowId`, then evaluate `WHERE` on current data.

#### Isolation levels

Only **READ COMMITTED** is implemented. `REPEATABLE READ` is an enum stub; **SERIALIZABLE** and **MVCC snapshot** are not wired. ENGINE **X** is checkpoint quiesce, not a stronger isolation level.

| Phenomenon | This engine (RC + 2PL) | REPEATABLE READ (not here) | SERIALIZABLE / snapshot (not here) |
|------------|------------------------|----------------------------|-------------------------------------|
| **Dirty read** (see uncommitted write) | **No** — reader waits on row **X**, then re-reads live heap | No | No |
| **Non-repeatable read** (same row, two `SELECT`s, different committed values) | **Yes, allowed** | No (hold S / snapshot) | No |
| **Phantom** (second `SELECT` sees new committed rows) | **Yes, allowed** — no gap / next-key locks | Often still yes without next-key | No |
| **Write skew** (two txns each read then write disjoint rows) | **Yes, allowed** | Yes under locking RR | No under true serializable / SSI |
| **Lost update** (two writers, last commit silently overwrites) | **No on the same row** — second writer waits on **X** | No | No |

#### READ COMMITTED can feel like a bug

These are **by design**. Manual walkthroughs: `docs/tests/transaction-concurrency.md` (especially scenario 4).

| What you see | Why it is not a bug |
|--------------|---------------------|
| In one `BEGIN`, first `SELECT` is `Ada`, second `SELECT` is `Updated` after the other client committed | **S** locks drop at **statement end**. The next statement reads whatever is committed now. |
| `SELECT *` in the same txn grows after another client `INSERT`s and commits | Phantom: no predicate/gap lock. New row ids were never locked. |
| Two sessions each `SELECT` then `UPDATE` a **different** row and both commit; an invariant across rows is broken | Write skew. RC does not lock “the set I read.” |
| Other client **blocks** on `SELECT` until you `COMMIT`/`ROLLBACK` | Correct 2PL: they must not dirty-read your **X**. After you rollback they see the old committed value (cascadeless). |
| Other client **does not** block on a **different** row | Table **IS**+**IX** are compatible; only that row is **X**. |
| `UPDATE`/`DELETE` return `OK` but zero rows changed | Statement finished; `OK` is not a row count. |

If a reader returns **uncommitted** data (`Hidden` while the writer has not committed), or still sees `Hidden` after the writer `ROLLBACK`s, that **is** a bug (stale scan / missing post-lock re-read).

### Concurrency control

- **Hierarchy** — ENGINE → CATALOG → DATABASE → TABLE → ROW (coarse before fine).
- **Modes** — **IS**/**IX** on engine/database/table; **S**/**X** on rows; ENGINE **X**, table **X**, database **X** for checkpoint / DDL / drop database.
- **Deadlock** — wait-for graph, **DETECT_RESOLVE**, abort youngest; undo + end explicit session.
- **Lock wait** — default **30s**; timeout rolls back an open explicit transaction.
- **DDL persist** — short exclusive catalog lock after ENGINE IX + table/db locks. Catalog memory is locked so `ROLLBACK` restore cannot hide tables from other threads.
- **CHECKPOINT** — ENGINE **X**, then catalog exclusive (flush WAL → `flushAll` → WAL fence). **Error** if this session or any other has `BEGIN` open.

#### Statement → lock sequence

| Statement | Locks (order) | Held until |
|-----------|---------------|------------|
| `SELECT` | ENGINE **IS** → db **IS** → table **IS** → row **S** | Statement end (shared) |
| `INSERT` / `UPDATE` / `DELETE` | ENGINE **IX** → db **IX** → table **IX** → row **X** | COMMIT / ROLLBACK |
| Table DDL / `CREATE INDEX` | ENGINE **IX** → db **IX** → table **X** → catalog X | Statement |
| `DROP DATABASE` | ENGINE **IX** → database **X** → catalog X | Statement |
| `CREATE DATABASE` | ENGINE **IX** → catalog X | Statement |
| `CHECKPOINT` | ENGINE **X** → catalog X | Checkpoint |

#### What’s allowed?

| Situation | Result |
|-----------|--------|
| Two `SELECT`s | Concurrent (ENGINE IS + table IS) |
| `SELECT` + `INSERT` same table, different rows | Concurrent (table IS + IX); only the written row is **X** |
| `SELECT` + writer on the **same** row | Reader **waits** on row **X** |
| DML on `orders` while DDL on `users` | Concurrent (different table keys) |
| Same-table DML vs table DDL | **Wait** (table **X** vs **IX** / **IS**) up to lock timeout |
| Anything vs `CHECKPOINT` | **Wait** on ENGINE **X** |
| Work in `shop` vs `DROP DATABASE shop` | **Wait** on database **X** |
| `CHECKPOINT` while any `BEGIN` is open | **Error** (refused), no wait |

Same-table DDL mixed with uncommitted writers is a **lock convoy** (table **X** waits on every leftover table **IX**; FIFO; `CHECKPOINT` also waits on ENGINE **IX**). That is why the hard stress test does not run concurrent `CREATE INDEX` / `ALTER TABLE`. A session that already holds table **IX** cannot take table **X** until it commits (upgrade wait / self-wait until timeout).

### Recovery & durability

- **WAL** — catalog DDL plus logical `INSERT_ROW` / `UPDATE_ROW` / `DELETE_ROW` / `INDEX_*`; `COMMIT` flushes the log (**no-force** of data pages).
- **WAL-before-data** — dirty `.ibd` flush calls `flushUpTo(pageLsn)` first.
- **Checkpoint** — ENGINE X: WAL flush → `bufferPool.flushAll()` → CHECKPOINT fence.
- **Startup** — catalog replay → redo committed DML → `IndexPageWal` for `.idx`.
- **Live rollback** — RAM undo only (not crash undo). Restored rows may get a new RID; indexes are maintained from that heap undo. Locks are released even if undo throws. `ROLLBACK` of `CREATE INDEX` / `CREATE TABLE` also deletes the new `.idx` / `.ibd`.
- **Unique insert** — duplicate unique/PK after the heap write deletes the orphan row and returns `ERROR: duplicate key…`.
- **Replay report** — `data/replay/` on start.

### Networking & operations

- **TCP** — one accept thread; each connection runs queries sequentially.
- **Disconnect** — rolls back an open explicit transaction on that connection.
- **Config** — `server.env` / environment for data dir, checkpoint strategy, lock wait, WAL size, `PAGE_SIZE`, `INDEX_KEY_PADDING_BYTES`, `BUFFER_POOL_FRAMES`.

### Mixed-workload stress

JUnit `@Tag("stress")` harnesses: many threads, one `StorageEngine`, ThreadLocal txn per worker. They check **survival + PK uniqueness** (then restart), not serializability.

| | Lean | Hard |
|--|--|--|
| Class | `MixedWorkloadLeanStressTest` | `MixedWorkloadHardStressTest` |
| Load | 4 × 200 ops, seed 42 | 6 × 250 ops, seed 99 |
| Mix | ~40% SELECT, rest DML, rare `CHECKPOINT` | ~30% SELECT, ~55% DML, explicit txn, more `CHECKPOINT` |
| DDL in storm | No | No (table **X** convoy) |
| Log | `logs/mixed-workload-lean-stress.log` | `logs/mixed-workload-hard-stress.log` |

Tags: `OK`, `ERR_ALLOWED` (duplicate key, lock timeout, txn aborted, `CHECKPOINT`/`BEGIN`/`COMMIT`/`ROLLBACK` protocol errors), `ERR_UNEXPECTED`, `FATAL`. After the storm both tests assert unique PKs on `shop.users` / `shop.orders` and again after a new engine on the same directory.

---

## Limitations

### Storage & indexing

- **No steal** — dirty frames are not clock-evicted until flush/CHECKPOINT (pool can fill under heavy DML).
- **Logical redo only** for the heap; `.idx` uses a separate `IndexPageWal` stream.
- **PRIMARY KEY** is single-column only. No foreign keys, check constraints, or triggers.

### Concurrency & isolation

- **Not MVCC** — 2PL + undo, not snapshot reads.
- **`REPEATABLE READ` / `SERIALIZABLE`** — not implemented. Non-repeatable read, phantom, and write skew **feel like bugs** under RC; they are listed in Features.
- **Write prefilter** — `UPDATE`/`DELETE` with `WHERE col = literal` may filter the scan snapshot before locking; other predicates lock scanned rows first.
- **Do not mix table DDL with a busy write workload** — expect long lock waits, not concurrent progress. Run DDL when the table is idle.

### SQL & protocol

- Lexer rejects trailing `;`.
- No joins, aggregates, `ORDER BY`, `LIMIT`, subqueries; string compare is case-sensitive.
- `UPDATE`/`DELETE` return `OK` even if zero rows match.
- No auth, TLS, or multi-statement batches.

### Scale

- Single process. No replication or distributed consensus.
- No statistics or join planner; index-only access is equality/range scan, not covering-index-only.

---

## Phase guarantees (summary)

| Property | Status |
| -------- | ------ |
| **READ COMMITTED** | Implemented |
| **Strict 2PL (writes)** | Implemented |
| **Row-level IS/IX/S/X** | Implemented |
| **ENGINE lock (checkpoint quiesce)** | Implemented |
| **Live undo rollback** | Implemented |
| **Deadlock detection** | Implemented |
| **Cascadeless reads (post-lock re-read)** | Implemented |
| **File heap + buffer pool** | Implemented |
| **B+ tree indexes** | Implemented |
| **PRIMARY KEY** | Implemented (single-column) |
| **B+ internal merge/borrow** | Implemented |
| **Best-index heuristic** | Implemented |
| **Durable DML (logical redo)** | Implemented |
| **Concurrent mixed DML + restart PK check** | Exercised (lean/hard stress) |
| **Concurrent table DDL under load** | Not supported |
| **MVCC / snapshot isolation** | Not implemented |
| **REPEATABLE READ** | Not implemented |
| **Steal / dirty eviction** | Not implemented |

---

## Related docs

- **LLD** — `docs/lld/java-database-lld.md`, `.puml`, `.txt`
- **Concurrency design** — `docs/temp-dev-notes/Transaction Concurrency & Recovery Design.md`
- **Manual client tests** — `docs/tests/README.md`, `docs/tests/client-manual.md`, `docs/tests/transaction-concurrency.md`
- **Stress tests** — `MixedWorkloadLeanStressTest`, `MixedWorkloadHardStressTest`; logs under `logs/`
- **Wire protocol** — `docs/protocol/wire-protocol.md`
