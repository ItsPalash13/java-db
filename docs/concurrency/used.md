# Where concurrency is used (today)

Snapshot of **threads, locks, latches, and atomics** that actually run. Related design: `docs/temp-dev-notes/BufferPool.md`, `docs/temp-dev-notes/Lock-scopes.md`, `docs/product/README.md`.

---

## Threads (network + background)

- `main`: `Thread.currentThread().join()` so the process stays up (accept/workers are daemons).
- `db-shutdown`: JVM shutdown hook thread calls `server.stop()` on Ctrl+C / SIGTERM.
- `db-accept`: one daemon thread; blocking `accept()` so `TcpNetworkModule.start()` can return.
- `db-conn-*`: `java.util.concurrent` cached daemon pool; one worker per open client.
- On each `db-conn-*` thread: receive → `RequestHandler` → `QueryProcessor` → `QueryDispatcher` → executor → send (same thread, no extra pool).
- `TcpNetworkModule.handle` `finally`: `RequestHandler.onConnectionClosed()` → `QueryProcessor.endConnectionSession()` rolls back an open explicit txn on that worker and releases locks / catalog hold.
- `checkpoint-scheduler`: optional daemon (`CheckpointScheduler`) when `CHECKPOINT_ENABLED`; under ENGINE X: `wal.flush` → `bufferPool.flushAll` → `wal.checkpoint`.
- Stop: close the listen socket (unblocks `accept`), then `workers.shutdownNow()` + `awaitTermination`; stop checkpoint scheduler; `BufferPool.flushAll()`.

---

## SQL locks (`LockManager` / `DefaultLockManager`)

Logical concurrency between sessions. Owner id is **txn id** when `bindOwner` is set (not thread id).

| What | How |
|------|-----|
| Hierarchy | **ENGINE → CATALOG → DATABASE → TABLE → ROW** |
| `CHECKPOINT` | **ENGINE X** then exclusive catalog lock (`runWithEngineX` + `runExclusiveCatalog`) |
| Catalog DDL persist | Exclusive catalog lock after ENGINE IX + table/db locks |
| Lock table metadata | `stateMutex` (`ReentrantLock`) + per-key waiters (`Condition`); not a SQL table lock |
| DQL | ENGINE **IS**, database **IS**, table **IS**, row **S** |
| DML | ENGINE **IX**, database **IX**, table **IX**, row **X** |
| DDL | ENGINE **IX**, then table **X** / database **X**, then catalog X for persist |
| READ COMMITTED reads | Row **S** + ENGINE/table **IS** released at **statement end** (`unlockSharedForOwner`); ENGINE **IX** kept |
| Writes | ENGINE **IX** + row **X** (+ table **IX**) held until **COMMIT** / **ROLLBACK** |
| Read-your-writes | Holding row **X** skips conflicting **S** wait on the same row |
| Deadlock | Wait-for graph, `DETECT_RESOLVE`; default victim = youngest txn (`ABORT_YOUNGEST`) |
| Disconnect | `unlockAllForOwner` after rollback of any open explicit session |

Volcano (`SeqScan`, `UpdateOperator`, `DeleteOperator`, …) takes these locks. Post-lock re-read via `findByRowId` keeps RC cascadeless. ENGINE X does **not** change READ COMMITTED — it only quiesces traffic for checkpoint.

---

## Transactions + undo (same connection threads)

- Implicit DML: `TransactionManager.runInTransaction` on the worker thread.
- Explicit: `BEGIN` / `COMMIT` / `ROLLBACK` via `TransactionControlExecutor` on that same worker.
- `UndoManager` records before-images; rollback applies newest-first through `UndoableTableStore` → `InMemoryTableStore` (still RAM heaps).
- No extra undo/txn thread pool.

---

## Buffer pool pin / latch (wired, idle for DML)

`DefaultBufferPool` is constructed by `DefaultStorageEngine`. **Volcano does not call `pin` yet** — DML still uses `InMemoryTableStore`. Pin/latch matter for tests and for Phase 4 FileTableStore.

| Name | Question | Hold | Owner |
|------|----------|------|--------|
| **Pin** | May this frame be evicted? | while using the page in RAM | `BufferPool` (`pinCount`) |
| **Latch** (S/X per frame) | May I read/write these bytes now? | microseconds — **never until COMMIT** | `BufferPool` (`ReentrantReadWriteLock`) |
| **Lock** | May this session see/change this table/row? | statement (S) or COMMIT (X) | `LockManager` |

- Pool metadata uses an internal `tableLock` object (frame map / clock); not a SQL lock.
- Phase 3: clock **never evicts dirty** frames (no-steal until DML WAL); dirty pages leave RAM via `flush` / `flushAll` (including engine `stop`).
- Catalog JSON and `wal.log` stay **off** the pool.

Do **not** treat page latches as lock scopes. Hierarchy is engine → catalog → database → table → row (`docs/temp-dev-notes/Lock-scopes.md`).

---

## Atomics / flags (not SQL concurrency)

- `TcpNetworkModule.running`: `AtomicBoolean` for idempotent start/stop and accept-loop exit.
- Worker name counter: `AtomicInteger` in the thread factory (`db-conn-1`, …).
- `StorageEngine.running`: `AtomicBoolean`; `catalogManager()` / `bufferPool()` / … fail if not started.
- `InMemoryTableStore.nextRowId`: `AtomicLong` for heap row ids (single-process id allocator).

---

## Not a thread pool (names that look like concurrency)

- `QueryDispatcher`: lookup `QueryType` → `QueryExecutor`, then `execute()` on the caller thread.
- `ExecutorRegistry`: map only; no scheduling.
- `HeapPage` / `RowCodec`: pure codecs; no locks.
- `PhysicalStorage`: filesystem bytes; callers serialize logically via locks/WAL, not a storage thread pool.

---

## Mentally: three layers

```text
db-conn-* worker
  │  LockManager     (SQL IS/IX/S/X — statement or until COMMIT)
  │  TransactionManager + UndoManager
  │
  └─ (Phase 4+) BufferPool pin → latch → HeapPage bytes → unlatch → unpin
                 short physical protection of a frame only
```

**Build path:** Phase 1 locks/txns (done) → Phase 2 page codecs (done) → Phase 3 BufferPool (done, unused by DML) → Phase 4 file heap uses pin/latch → Phase 5 index crabbing latches.
