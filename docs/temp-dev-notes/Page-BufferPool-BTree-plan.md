# Page, BufferPool, B+ tree & disk flush — build plan

**Path:** Page layout → BufferPool (+ flush) → File heap → B+ tree → DML WAL  
(Do not start with in-memory B+ tree and retrofit disk later — that rewrites I/O twice.)

Related: `docs/temp-dev-notes/BufferPool.md`, `docs/product/README.md`

---

## Done — Phase 1 (READ COMMITTED + Strict 2PL)

- Volcano executor + WHERE on `InMemoryTableStore`
- Row/table locks (IS/IX/S/X) in Volcano + DDL locks in `CommandExecutor`
- Explicit `BEGIN` / `COMMIT` / `ROLLBACK`
- **READ COMMITTED:** release **S** + table **IS** at statement end; keep **X** / **IX** until COMMIT/ABORT
- **Strict 2PL on writes**; read-your-writes (X bypasses S on same row)
- **`UndoManager`** before-images; rollback newest-first (not full heap snapshot)
- Deadlock: wait-for graph + victim abort (`DETECT_RESOLVE`)
- Post-lock re-read (`findByRowId`) on `SeqScan` / `UpdateOperator` / `DeleteOperator`
- Tests: cascadeless read, non-repeatable read allowed, write-path lock-before-predicate, undo, deadlock

---

## Phase 2 — Page layout

- Fixed page size (16 KiB; small pages in tests)
- Header + **slot directory** + row bytes for `INT` / `VARCHAR` / `BOOLEAN`
- Define row address: keep internal `rowId`, map to `(pageId, slotId)` on heap pages
- Pure codecs / layout — no pool, no tree yet

---

## Phase 3 — BufferPool + flush to disk

- `BufferPool`: frames, pin/unpin, latch S/X, dirty bit, clock eviction
- I/O only via `PhysicalStorage` (`offset = pageId * pageSize`)
- `flush` / `flushAll` for eviction, checkpoint, and `StorageEngine.stop`
- First policy: **no-steal** (do not evict dirty pages of open txns) until DML WAL exists
- Wire pool in `StorageEngine`; Volcano never calls pin — only `TableStore` / later `IndexStore`
- Catalog JSON and `wal.log` stay **off** the pool

Detail: `docs/temp-dev-notes/BufferPool.md`

---

## Phase 4 — File heap `TableStore`

- Page-backed heap through BufferPool (replace or wrap `InMemoryTableStore`)
- `insert` / `scan` / `update` / `delete` / `findByRowId` via pin → latch → slot → unpin
- Keep existing row locks + undo + post-lock re-read
- Milestone: restart server → `SELECT` still returns rows (dirty pages flushed on stop/checkpoint)
- `DROP TABLE` deletes data files, not only catalog JSON

---

## Phase 5 — B+ tree `IndexStore` (same pool)

- Leaf / internal node layout on the **same** page format
- Search, insert, split, leaf-chain range scan
- Latch **crabbing** (parent → child → unlatch parent)
- Flesh out `IndexStore`; wire `CREATE INDEX` + `IndexScanOperator` + planner `INDEX_SCAN`
- Maintain indexes on INSERT / UPDATE (indexed cols) / DELETE
- Index undo records (with heap undo) for explicit txn rollback

---

## Phase 6 — DML WAL + WAL-before-data

- Log insert/update/delete (or page redo) **before** a dirty page may hit disk
- Eviction: flush WAL up to page LSN → write page → flush file
- `COMMIT`: flush WAL only (**no-force** pages); recovery redoes
- Extend `CHECKPOINT` to flush dirty pages after WAL
- Integrate with existing `UndoManager` for crash undo/redo

---

## Phase 7 — Hardening

- **PRIMARY KEY** / unique index enforcement
- **REPEATABLE READ:** hold **S** locks until COMMIT (isolation flag)
- Index delete: merge/underflow (if deferred)
- Phantom/gap locks (optional)
- LLD + integration tests kept in sync with each phase

---

## Explicitly later

- Eager `QueryDispatcher`
- Graceful TCP shutdown
- Performance analysis

---

## Rules of thumb

| Layer | Owns | Hold until |
|-------|------|------------|
| **Row locks** (`LockManager`) | SQL / txn concurrency | statement (S) or COMMIT (X) |
| **Page latches** (BufferPool) | heap/tree bytes in a frame | microseconds — never until COMMIT |
| **BufferPool** | RAM frames ↔ disk pages | pin while using frame |
| **WAL** | durable change log | flush before dirty data page write |

**Build order one-liner:** Page layout → BufferPool (+ flush) → File heap → B+ tree → DML WAL.
