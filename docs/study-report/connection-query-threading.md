# Study Report: Connection Threading — This Project vs SQLite, PostgreSQL, SQL Server

Context: `TcpNetworkModule`, `DefaultRequestHandler`, `QueryDispatcher`, and `database-client`.
Related: [accept-concurrency.md](./accept-concurrency.md) (how `accept()` hands work to workers).

---

## 1. Question

Does this database open one thread per connection and one thread per query?

## Answer

**One worker thread per connection, not per query.**

| Scope | Thread model |
|---|---|
| Listen / accept | One dedicated thread: `db-accept` |
| Each TCP client | One pool worker: `db-conn-1`, `db-conn-2`, … |
| Each SQL statement on that client | Same worker — no new thread |

### Call chain in this project

```text
TcpNetworkModule.acceptLoop()          // db-accept
  → workers.submit(() -> handle(conn)) // db-conn-N starts

handle(connection)                     // same db-conn-N for life of socket
  loop:
    connection.receive()               // block until framed request
    requestHandler.handle(request)     // decode → QueryProcessor
    connection.send(response)
```

Relevant code:

- `TcpNetworkModule.handle()` — comment: *"One worker owns one connection for its lifetime"*
- `QueryDispatcher` — comment: *"Not a thread pool: network workers already own the request from receive through execute."*

So the connection thread **blocks** for the full pipeline: network I/O → lex → parse → analyse → plan → execute → response I/O. The next request on that socket is not read until the current one finishes.

### Thread inventory (typical running server)

```text
Main thread          — Thread.join(); keeps JVM alive
db-accept            — accept loop only
db-conn-N (0..many)  — one per live TCP connection
db-shutdown          — shutdown hook on Ctrl+C (not steady state)
```

Workers come from `Executors.newCachedThreadPool()`: created on demand, reused after a connection closes.

---

## 2. Question

So the same connection thread runs the query?

## Answer

**Yes.** There is no handoff to another executor inside the query layer.

```text
db-conn-1:
  receive()
    → DefaultRequestHandler.handle()
      → QueryProcessor.execute(String)
        → QueryDispatcher.execute(plan)   // caller thread
          → CommandExecutor.execute(plan)   // caller thread
    → send()
  (loop)
```

Implications for this codebase today:

- **One client, one connection, REPL-style:** queries run **serially** — correct for an interactive console.
- **Many clients:** each connection has its own worker; different clients can run queries **in parallel** (until shared storage needs locking — not fully built yet).
- **Long query:** that client's worker is blocked until completion; the client must wait for the response frame before sending the next request.

The client (`database-client`) is also synchronous: `DatabaseClient.execute()` does `send()` then `receive()` on the calling thread.

---

## 3. Question

Is the TCP connection a "session"?

## Answer

**Transport session yes; application session no.**

| Layer | Persistent? | What exists today |
|---|---|---|
| TCP connection | Yes | Server loops until client closes socket |
| `ConnectionId` | Per connection | Assigned in `TcpClientConnection`; for logs / future maps — **not** passed to `QueryProcessor` |
| SQL session state | No | No `USE db`, no per-connection catalog context, no transactions |
| Auth | No | Any TCP peer can send SQL text |

Wire protocol: length-prefixed UTF-8 frames — `[4-byte big-endian length][payload]`, max 1 MiB. One frame in, one text result out (`OK …`, `ERROR …`, etc.).

Multiple queries on **one** TCP connection work without server changes. Each query is still **stateless** at the engine layer (use qualified names like `database.table`).

---

## 4. Question

How do SQLite, PostgreSQL, and SQL Server compare?

## Answer

All serious clients use a **long-lived connection** and typically **one in-flight request per connection** at the protocol level. Internals differ: embedded library vs server, thread vs process vs scheduler pool.

### Summary table

| | **This Java DB** | **SQLite** | **PostgreSQL** | **SQL Server** |
|---|---|---|---|---|
| **Architecture** | Network server (JVM) | Embedded library (in-process) | Network server | Network server |
| **Unit per client** | 1 JVM thread (`db-conn-N`) | 1 `sqlite3*` connection handle | 1 backend **process** | 1 **session** (logical connection) |
| **Who runs the query?** | Same connection worker thread | Thread calling the SQLite API | Same backend process | SQLOS **worker thread pool** (tasks) |
| **Queries per connection** | One at a time (blocking loop) | One at a time per connection | One at a time per connection* | One at a time per session* |
| **Cross-client parallelism** | Different connection threads | File-level locking; one writer | Different backend processes | Many sessions → scheduled workers |
| **Pooling** | Not implemented | N/A (in-process) | PgBouncer common (processes are heavy) | Client + server pooling common |

\* Postgres and SQL Server can add **intra-query parallelism** (extra workers for one large query). That is separate from "one connection, one active client request."

---

### SQLite — in-process, not a connection thread model

SQLite is a **library** linked into the application, not a daemon with an accept loop.

- Open a **`sqlite3*` connection**; the **calling thread** runs `sqlite3_step()` / `sqlite3_exec()`.
- No server-side "connection thread" unless the app creates one.
- Threading modes (simplified):
  - **Single-thread:** only one thread may use SQLite.
  - **Multi-thread:** each connection should be used by one thread at a time.
  - **Serialized:** internal mutex allows sharing one connection across threads safely.
- **Write concurrency:** even with WAL, **one writer at a time** to the database file; multiple readers are OK.

**Contrast with this project:** we are a **remote TCP server** with explicit accept + worker threads. SQLite is **local API calls + file locks**.

---

### PostgreSQL — process per connection (classic model)

```text
Client ──TCP──► postmaster (listen)
                    │
                    └── fork/spawn ──► backend process (1 per connection)
                              │
                              └── read msg → parse → plan → execute → reply → loop
```

- **Postmaster** accepts; each new client gets a dedicated **backend process**.
- That process owns the connection for its lifetime and handles queries **sequentially** on that connection (same high-level pattern as our `handle()` loop).
- Shared buffer pool and locks live in **shared memory**; process isolation limits blast radius of a client crash.

**Analogy to this project:** closest mental model — **one long-lived worker per client, sequential queries on that worker** — but Postgres uses **OS processes** and full storage/concurrency subsystems (MVCC, WAL, lock manager).

**PgBouncer** exists because a Postgres backend is expensive; pooling reuses backends across many short-lived clients.

---

### SQL Server — session per connection, internal thread pool

```text
Client ──TDS──► listener
                    │
                    └── session (login, current db, txn, settings)
                              │
                              └── requests → tasks on SQLOS schedulers
                                        │
                                        └── worker threads (pool) run tasks
```

- Each client gets a **session** with connection-scoped state.
- Internally, SQL Server does **not** keep one dedicated OS thread per connection forever.
- **SQLOS** schedules **tasks** onto a **worker thread pool**; workers serve many sessions over time.
- Client-side connection pooling (JDBC, ADO.NET) is standard.

**Contrast with this project:** we map **connection → worker thread → synchronous execute** directly. SQL Server adds a **scheduler indirection** between session and OS threads for scale and CPU management.

---

## 5. Question

Where does this project sit on that map?

## Answer

```text
Today ≈ PostgreSQL's "one worker per connection, sequential queries"
        implemented with JVM threads + cached thread pool
        + shared QueryProcessor / StorageEngine (like shared PG buffers)
        − no session state, MVCC, or production lock manager yet
```

```text
┌─────────────────────┐         TCP (persistent)         ┌──────────────────────────┐
│  database-client    │  ── frame: SQL text ──────────►  │  TcpNetworkModule        │
│  DatabaseClient     │  ◄── frame: result text ───────  │  db-conn-N: receive →   │
│  (REPL optional)    │       repeat until close         │    handle → send (loop)  │
└─────────────────────┘                                  └───────────┬──────────────┘
                                                                       │
                                                                       ▼
                                                           DefaultRequestHandler
                                                                       │
                                                                       ▼
                                                           DefaultQueryProcessor
                                                          (same thread, no pool)
                                                                       │
                                                                       ▼
                                                           StorageEngine / Catalog
```

**SQLite:** different category — embedded, not comparable at the network layer.

**PostgreSQL:** same *connection ownership* idea; they use processes and decades of storage engineering.

**SQL Server:** same *session* idea at the wire; execution goes through an internal **scheduler + thread pool**, not a 1:1 dedicated thread for the connection lifetime.

---

## 6. Question

What would change for "application session" later?

## Answer

TCP persistence is already enough for a client console REPL. **Application** session (e.g. `USE mydb`, transactions tied to a connection) needs server-side state:

1. Pass `ConnectionId` (or a `Session` object) from `TcpNetworkModule.handle()` into `RequestHandler`.
2. Store per-connection fields: current database, transaction id, isolation level, etc.
3. Teach analyser / executor to resolve unqualified table names against session context.
4. Add concurrency control when multiple `db-conn-N` threads touch shared catalog or table data.

The network layer is already shaped for that (`ConnectionId` comment: *"useful for logs and future connection maps"*).

---

## Project files involved

| File | Role |
|---|---|
| `database-server/.../tcp/TcpNetworkModule.java` | Accept thread + per-connection worker loop |
| `database-server/.../tcp/TcpClientConnection.java` | Framed I/O; assigns `ConnectionId` |
| `database-server/.../requesthandler/DefaultRequestHandler.java` | Decode request → `QueryProcessor.execute()` |
| `database-server/.../processor/executor/QueryDispatcher.java` | Runs plan on caller thread |
| `database-server/.../processor/DefaultQueryProcessor.java` | Full query pipeline (shared across connections) |
| `database-client/.../DatabaseClient.java` | Client API: `execute()` over one socket |
| `database-client/.../ClientConnection.java` | Client-side length-prefixed framing |

---

## Further reading in this repo

- [accept-concurrency.md](./accept-concurrency.md) — `accept()`, kernel backlog, worker hand-off
- [accept-thread-daemon.md](./accept-thread-daemon.md) — why `db-accept` and workers are daemon threads
- `docs/lld/java-database-lld.txt` — network interfaces and ownership (`NetworkModule` → `RequestHandler`)
