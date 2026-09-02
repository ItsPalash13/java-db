# JavaDatabase — Product Overview

A **learning-oriented relational database** implemented in Java. Clients send SQL over TCP; the server lexes, parses, analyses, plans, and executes queries through a **Volcano-style pull executor** over an in-memory heap, with **catalog durability** via JSON files and a **write-ahead log (WAL)**.

---

## What it is

- **Embedded-style server process** — `database-server` listens on a TCP port; `database-client` is an interactive REPL.
- **SQL subset** — DDL, DML, DQL, explicit transaction control, and manual `CHECKPOINT`; queries use fully qualified names (`database.table`).
- **Educational scope** — correctness and visible concurrency behavior matter more than production throughput or full SQL compatibility.
- **Not a full RDBMS yet** — no authentication, no connection pooling, no prepared statements, no cost-based optimizer.

---

## Architecture

- **Query pipeline** — `Lexer` → `Parser` → `Analyser` (catalog name resolution) → `Planner` → `QueryDispatcher` → `CommandExecutor` or `VolcanoExecutor`.
- **Storage engine** — `CatalogManager` (schema on disk) + `InMemoryTableStore` (row heap in RAM) + `PhysicalStorage` (filesystem bytes).
- **Execution model** — Volcano operators (`SeqScan`, `Filter`, `Project`, `Insert`, `UpdateOperator`, `DeleteOperator`) compiled per plan; vectorized/batch executors exist as stubs only.
- **Wire protocol v1** — length-prefixed TCP frames; request = UTF-8 SQL; response = JSON (`OK`, `ERROR`, `RESULT_SET`, `DONE`). See `docs/protocol/wire-protocol.md`.

---

## Features

### SQL & catalog

- **Databases & tables** — `CREATE DATABASE`, `CREATE TABLE`, `DROP DATABASE`, `DROP TABLE`.
- **Schema changes** — `ALTER TABLE … ADD COLUMN`, `ALTER TABLE … DROP COLUMN`.
- **Index metadata** — `CREATE INDEX`, `DROP INDEX` (catalog definitions only; no B+ tree yet).
- **Introspection** — `DESCRIBE`, `SHOW DATABASES`, `SHOW TABLES`.
- **DML / DQL** — `INSERT`, `SELECT` (projections + `WHERE`), `UPDATE` (`SET` + `WHERE`), `DELETE` (`WHERE`).
- **Column types** — `INT`, `VARCHAR`, `BOOLEAN` (all columns nullable by default).
- **Qualified naming** — `shop.users`; no `USE database` session state.

### Transactions & isolation

- **Implicit transactions** — each auto-commit DML runs inside `TransactionManager.runInTransaction` with row **X-locks** held until commit/abort.
- **Explicit transactions** — `BEGIN` / `COMMIT` / `ROLLBACK`; catalog snapshot taken at `BEGIN`; DML changes rolled back via **undo log**, not heap snapshot restore.
- **Isolation level** — **`READ COMMITTED`** (only level implemented today).
- **Locking reads** — `SELECT` acquires table **IS** + row **S** locks; locks released at **statement end** (`unlockSharedForOwner`), not at transaction end.
- **Strict 2PL on writes** — table **IX** + row **X** locks held until `COMMIT` / `ROLLBACK`.
- **Row-level granularity** — locks keyed by internal immutable **`rowId`**, not user column values.
- **Read-your-writes** — a transaction holding **X** on a row does not block on **S** for that same row.
- **Cascadeless reads** — readers blocked on an uncommitted writer re-read the **live heap row after the S-lock is granted**; they do not return stale scan-snapshot tuples.
- **Post-lock re-read on writes** — `UpdateOperator` / `DeleteOperator` take **X**, then `findByRowId`, then evaluate `WHERE` on current data.
- **Allowed anomalies under RC** — non-repeatable read, phantom read, and write skew are permitted by design at this isolation level.

### Concurrency control

- **Lock modes** — intention shared/exclusive (**IS** / **IX**) at database/table scope; **S** / **X** at row scope.
- **Deadlock handling** — **wait-for graph** with **DETECT_RESOLVE**; victim aborted (default: youngest transaction), undo applied, explicit session ended.
- **Lock wait timeout** — configurable (default **30s**); failure rolls back an open explicit transaction.
- **Catalog serialization** — DDL and `CHECKPOINT` take an exclusive catalog lock; DDL is WAL-logged before catalog persist.

### Recovery & durability

- **WAL (catalog DDL)** — `append` → `flush` → `COMMIT` marker; startup **replay** merges committed records after `catalog.load()`.
- **Checkpoint** — manual `CHECKPOINT` SQL or background scheduler (`timeout` or `wal_size`); updates `wal.checkpoint`, appends `CHECKPOINT` line (append-only `wal.log`).
- **Undo-based rollback** — `UndoManager` records insert/update/delete per transaction; `ROLLBACK` restores heap state through `UndoableTableStore`.
- **Replay diagnostics** — per-start replay report under `data/replay/`.

### Networking & operations

- **Multi-client TCP** — one accept thread; per-connection worker handles sequential queries.
- **Session cleanup** — disconnect rolls back any open explicit transaction on that connection.
- **Configuration** — `server.env` / environment variables for data directory, checkpoint strategy, lock wait, WAL size limits.

---

## Limitations

### Storage & indexing

- **Heap is volatile** — `InMemoryTableStore` holds rows in RAM; **restart loses all table data** while catalog (and WAL-replayed DDL) survives.
- **DML is not WAL-durable** — only catalog DDL is logged and replayed; there is no ARIES-style redo/undo of data pages.
- **No buffer pool / pages** — no slotted pages, no `FileTableStore`, no on-disk tuple storage.
- **No real indexes** — `CREATE INDEX` is metadata only; every access path is a **full heap scan** (`SeqScan`).
- **No PRIMARY KEY / UNIQUE** — no uniqueness enforcement; `id` is a normal column.
- **No foreign keys, constraints, or triggers.**

### Concurrency & isolation

- **Not MVCC** — no multi-version tuple chains or snapshot visibility; concurrency uses **2PL + undo**, closer to locking-read engines than to InnoDB-style **MVCC snapshot reads**.
- **`REPEATABLE READ` not implemented** — enum exists; behavior is not wired.
- **`SERIALIZABLE` not supported.**
- **Write-path prefilter edge case** — `UPDATE` / `DELETE` with `WHERE id = <literal>` may apply a pre-lock `Filter` on the heap snapshot; other predicates lock every scanned row first.
- **No predicate / gap / next-key locking** — phantoms and write skew remain possible under **READ COMMITTED**.

### SQL & protocol

- **No semicolons** — lexer rejects trailing `;`.
- **Limited expressions** — basic comparisons and literals; no joins, aggregates, `ORDER BY`, `LIMIT`, subqueries, or `NULL`-aware three-valued logic beyond what tests cover.
- **Case-sensitive string comparison** — `'Ada'` ≠ `'ADA'`.
- **`OK` is not row count** — `UPDATE` / `DELETE` return `OK` even when zero rows match.
- **No auth, TLS, or multi-statement batches** in the wire protocol.

### Scale & production readiness

- **Single-node, single process** — no replication, sharding, or distributed consensus.
- **In-memory scalability ceiling** — entire table heap lives in the JVM heap.
- **No query optimizer** — no statistics, no join ordering, no index-only plans.
- **Learning codebase** — APIs and on-disk formats may change between phases.

---

## Phase 1 guarantees (summary)

| Property | Status |
| -------- | ------ |
| **READ COMMITTED** | Implemented |
| **Strict 2PL (writes)** | Implemented |
| **Row-level IS/IX/S/X locks** | Implemented |
| **Undo rollback** | Implemented |
| **Deadlock detection** | Implemented |
| **Cascadeless reads (post-lock re-read)** | Implemented |
| **MVCC / snapshot isolation** | Not implemented |
| **REPEATABLE READ** | Not implemented |
| **Durable DML / page recovery** | Not implemented |
| **B+ tree indexes** | Planned |

---

## Related docs

- **LLD & wiring** — `docs/lld/java-database-lld.md`, `.puml`, `.txt`
- **Concurrency design** — `docs/temp-dev-notes/Transaction Concurrency & Recovery Design.md`
- **Manual concurrency tests** — `docs/tests/transaction-concurrency.md`
- **Wire protocol** — `docs/protocol/wire-protocol.md`
