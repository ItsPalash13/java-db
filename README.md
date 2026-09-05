# JavaDatabase

Learning-oriented, single-node **OLTP** relational database in Java (server + TCP client). Built to make concurrency, locking, WAL, and crash recovery visible—not to compete with production RDBMSs.

Full product detail: [`docs/product/README.md`](docs/product/README.md).

## How the engine is built

| Area | What this DB does |
|------|-------------------|
| **Workload** | Single-process OLTP: short SQL statements, multi-client concurrency |
| **Networking** | Length-prefixed TCP wire protocol; one accept thread; one worker thread per connection (queries sequential per session) |
| **Query pipeline** | Lexer → parser → analyser → planner → Volcano-style pull executor |
| **Storage** | Page-backed heaps (`.ibd`), B+ tree indexes (`.idx`), JSON catalog, shared buffer pool (pin/latch; **no-steal** for dirty frames) |
| **Transactions** | Auto-commit or explicit `BEGIN` / `COMMIT` / `ROLLBACK`; live rollback via RAM undo |
| **Isolation** | **READ COMMITTED** only (not MVCC, not REPEATABLE READ / SERIALIZABLE) |
| **Concurrency** | Strict 2PL; lock hierarchy ENGINE → CATALOG → DATABASE → TABLE → ROW; IS/IX + row S/X; wait-for-graph deadlock detection (abort youngest); lock timeouts |
| **Durability / recovery** | WAL (WAL-before-data, commit flushes log, no-force data pages); logical redo + checkpoint; startup catalog replay → redo → index WAL |
| **SQL surface** | DDL, DML, DQL, indexes, single-column PRIMARY KEY / UNIQUE; equality/range index scans |

Non-repeatable reads, phantoms, and write skew are allowed under RC by design; dirty reads and same-row lost updates are not.

## What it is / isn’t

| Is | Isn’t |
|----|--------|
| Server + TCP REPL client | Auth, TLS, connection pooling |
| SQL subset (DDL, DML, DQL, txn, checkpoint) | Full SQL (joins, aggregates, `ORDER BY`, subqueries) |
| Visible concurrency + crash/restart behavior | Cost-based optimizer, prepared statements |
| Single process, educational OLTP | Replication, distributed consensus |

Names are fully qualified (`database.table`); there is no `USE`. Statements must **not** end with `;`.

## Modules

Maven multi-module project (**Java 17**):

| Module | Role |
|--------|------|
| `database-server` | TCP listener, query pipeline, storage, WAL, locks |
| `database-client` | Interactive REPL, script runner, crash harnesses |

## Quick start

```powershell
mvn -q "-DskipTests" package
mvn -pl database-server exec:java "-Dexec.args=--port 9090 --data-dir data"
```

In another terminal:

```powershell
mvn -pl database-client exec:java "-Dexec.args=127.0.0.1 9090"
```

## Minimal SQL walkthrough

```text
CREATE DATABASE shop
CREATE TABLE shop.users (id INT, name VARCHAR)

INSERT INTO shop.users VALUES (1, 'Ada')
INSERT INTO shop.users VALUES (2, 'Bob')

SELECT * FROM shop.users
SELECT name FROM shop.users WHERE id = 1

UPDATE shop.users SET name = 'Ada Lovelace' WHERE id = 1
SELECT * FROM shop.users

DELETE FROM shop.users WHERE id = 2
SELECT * FROM shop.users
```

## Script mode

Run a file of statements (one per line; `#` / `--` comments and blanks skipped):

```powershell
mvn -pl database-client exec:java "-Dexec.args=--script input/cmds/load_1k.txt --out out/load_1k.run.txt 127.0.0.1 9090"
```

Optional: `--stop-on-error`.

## Project layout

```text
database-server/   # engine
database-client/   # REPL + harnesses
docs/              # product, LLD, protocol, tests, design notes
tools/page-graph/  # B+ tree / page HTML visualizer
scripts/           # one-shot load + page-graph helpers
input/cmds/        # sample SQL scripts
test-scripts/      # WAL / mixed crash verify scripts
```

## Architecture (sketch)

```text
Client (TCP) → RequestHandler → QueryProcessor
  Lexer → Parser → Analyser → Planner → Dispatcher
    → CommandExecutor (DDL / CHECKPOINT)
    → VolcanoExecutor (DML / DQL)
Storage: Catalog + FileTableStore (.ibd) + FileIndexStore (.idx)
         + BufferPool + PhysicalStorage + WAL
```

Locks are SQL-level (`LockManager`); buffer-pool latches are separate and short-lived. See LLD and wire protocol for types and framing.

## Features at a glance

- **Catalog / DDL** — `CREATE`/`DROP` database & table; `ALTER TABLE` add/drop column; `CREATE [UNIQUE] INDEX` / `DROP INDEX`; single-column `PRIMARY KEY`
- **DML / DQL** — `INSERT`, `UPDATE`, `DELETE`, `SELECT` (projections + `WHERE`); index scan for equality / one-sided range on a leading indexed column
- **Types** — `INT`, `VARCHAR`, `BOOLEAN`
- **Transactions** — auto-commit or `BEGIN` / `COMMIT` / `ROLLBACK`
- **Isolation** — `READ COMMITTED` + Strict 2PL (writes hold row **X** until commit)
- **Durability** — WAL, checkpoint, startup redo; live rollback via RAM undo
- **Introspection** — `DESCRIBE`, `SHOW DATABASES`, `SHOW TABLES`

## Docs map

| Doc | Contents |
|-----|----------|
| [`docs/product/README.md`](docs/product/README.md) | Product overview, isolation, locks, recovery |
| [`docs/lld/`](docs/lld/) | Class diagrams + text LLD (must match code) |
| [`docs/protocol/wire-protocol.md`](docs/protocol/wire-protocol.md) | Length-prefixed frames, JSON responses |
| [`docs/implementations/`](docs/implementations/) | Binding design decisions |
| [`docs/tests/`](docs/tests/) | Manual client + concurrency walkthroughs |
| [`docs/concurrency/used.md`](docs/concurrency/used.md) | Threads, locks, latches in use today |
| [`tools/page-graph/README.md`](tools/page-graph/README.md) | Index/page HTML graphs |

## Testing & harnesses

```powershell
mvn test
```

- Manual SQL: [`docs/tests/README.md`](docs/tests/README.md)
- Stress (`@Tag("stress")`): `MixedWorkloadLeanStressTest` / `MixedWorkloadHardStressTest`
- Optional crash scripts under `test-scripts/` (WAL / mixed); page-graph via `tools/page-graph` or `scripts/run_1k_page_graph.sh`

## Requirements

- **JDK 17+**
- **Maven 3.8+**
- Bash / Git Bash / MSYS for some `scripts/` and `test-scripts/` helpers on Windows
