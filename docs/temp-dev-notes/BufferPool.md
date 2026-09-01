# Buffer pool

RAM cache of **pages** (fixed-size disk blocks). Callers **pin** a page so it stays in a frame, **latch** the frame while they read or write bytes, then unpin. Dirty frames flush through `PhysicalStorage` offset I/O.

Not a lock manager. Not a page-format spec. Not WAL.

Related:

- `docs/temp-dev-notes/Lock-scopes.md` — SQL locks (db / table / row); pages are latches, not lock scopes
- `docs/todo` — Page + BufferPool + file TableStore (step 4); IndexStore trees after that
- `docs/temp-dev-notes/WAL-checkpoint-truncate.md` — checkpoint does **not** flush dirty pages yet (no pool)
- `PhysicalStorage` / `DefaultPhysicalStorage` — bytes and `flush()`; `pageSize` default 16 KiB; catalog still whole-file
- `storage/bufferpool/BufferPool.java` — empty interface; StorageEngine does not construct one

---

## What we have now

`BufferPool` is a stub. `TableStore` is `InMemoryTableStore` (RAM lists, lost on restart, not WAL-logged). Catalog JSON and `wal.log` go **straight** to `PhysicalStorage` (whole-file / append). Offset `read`/`write` already exist so later page I/O is `offset = pageId * pageSize` without reading the whole heap file.

Until this layer exists, Volcano never sees a page. Do not invent `readPage` on `PhysicalStorage` — the pool is the only caller of page-sized I/O.

---

## Why it exists

A heap or B+Tree is many 16 KiB pages on disk. Every `INSERT`/`SELECT` must not open the file and read the whole thing. The pool:

1. Keeps recently used pages in RAM (**frames**).
2. Stops two threads from tearing the same in-memory page (**latch**).
3. Stops eviction from throwing away a page a caller is still using (**pin**).
4. Decides **when** a dirty page hits disk (after the WAL record for that change is durable).

`TableStore` and `IndexStore` share one pool. Catalog JSON does not — rewriting a small metadata file is a different I/O shape.

---

## Three names (do not collapse)

| Name | Question | Held | Owner |
|------|----------|------|--------|
| **Pin** | May this frame be evicted? | while the caller still needs the page in RAM | BufferPool (`pinCount`) |
| **Latch** | May I read/write these bytes right now? | microseconds–milliseconds, never until COMMIT | BufferPool (per-frame S/X) |
| **Lock** | May this *session* see or change this table/row? | statement or until COMMIT | `LockManager` |

Pin and latch are **physical** (this process, this RAM copy). Lock is **logical** (this SQL object, other sessions). A `SELECT` can hold table S until COMMIT and still unpin/unlatch the page as soon as the slot is copied into a tuple.

```text
session:  lock table users S     (LockManager, maybe until COMMIT)
thread:   pin page 7
          latch page 7 shared
          copy slot → Tuple
          unlatch
          unpin
```

**Page locks** (a lock *scope* between table and row) are a third idea. This project skips them: table S/X first, row X later. A page is latched, not locked.

---

## Page vs frame

| | **Page** | **Frame** |
|--|----------|-----------|
| Lives | on disk (and as a copy in a frame) | one slot in the pool array |
| Identity | `(file, pageId)` — offset = `pageId * pageSize` | index in the frame table |
| Size | `PhysicalStorage.pageSize()` (16 KiB default) | same size + header (pinCount, dirty, latch, pageId) |

Fixed page size so the pool is a simple array and disk I/O is one aligned block. Catalog I/O ignores `pageSize` on purpose.

A **new** page is “append at `offset == fileLength`” (already allowed by `PhysicalStorage.write`). Holes (`offset > fileLength`) stay illegal.

---

## Pin / unpin

`pin(file, pageId)`:

1. If the page is already in some frame → `pinCount++`, return that frame.
2. Else pick a **victim** with `pinCount == 0`. If the victim is dirty, write it to disk first (only after WAL for those bytes is flushed). Load the requested page into that frame (`PhysicalStorage.read(file, pageId * pageSize, pageSize)`). `pinCount = 1`.
3. If every frame is pinned → wait or fail. Do **not** evict a pinned page (use-after-free of the caller's pointer into the frame).

`unpin`: `pinCount--`. At 0 the frame is eligible for eviction, not immediately written. Dirty unpinned pages sit until the clock hand needs the frame, or checkpoint / shutdown flush.

**Invariant:** a thread that will touch the bytes must pin first and unpin after. Missing unpin fills the pool with immortal pages and the next `pin` stalls forever.

Pin is **not** a latch. Two `SELECT`s may pin the same page (`pinCount = 2`) and both latch it shared. Pin only answers “keep this RAM.”

---

## Latch

Per-frame, two modes, same letters as locks but **not** the same object:

- **S** — many readers of this frame
- **X** — one writer; no readers

Hold the latch only while looking at or mutating the frame. Unlatch before:

- waiting on a SQL lock
- doing unrelated I/O (e.g. WAL append of a large record, if that can block)
- returning a tuple up the Volcano pipeline for the rest of the statement

Never hold a latch until COMMIT. That would serialize every session that needs that page, even readers of other rows on it, and it would invert the lock/latch layers.

**Acquire order (avoid latch deadlock):** pin, then latch. For B+Tree later, **crabbing**: latch parent, latch child, unlatch parent — never hold two tree latches in a cycle. Heap access is one page at a time for the first file `TableStore`.

Latches do **not** go through `LockManager`. No timeout, no wait-for graph. Order + short hold is the protocol. A latch wait that lasts like a lock wait is a bug (someone forgot to unlatch).

---

## Dirty, WAL, flush

A frame is **dirty** if the RAM copy differs from disk. `markDirty` after a write under an X latch.

**WAL-before-data** (when DML WAL exists): do not write a dirty page to disk until the log records that describe that change are flushed. Otherwise a crash can show a heap page with an INSERT whose log line never made it — recovery cannot undo or redo correctly.

Today `WalOp` is catalog-only and checkpoint does not flush pages. When the pool exists:

| Event | Pages |
|-------|--------|
| `unpin` of a dirty frame | stay in RAM (do not force to disk) |
| Eviction of a dirty victim | flush WAL up to that page’s LSN, then `PhysicalStorage.write` + usually `flush` that file |
| `COMMIT` | flush WAL; **do not** require all dirty pages of that txn on disk (no-force). Recovery redos. |
| `CHECKPOINT` | flush dirty pages (after WAL), then write the checkpoint record — so replay has a shorter tail |
| `StorageEngine.stop` | flush dirty pages so a clean shutdown does not rely on redo |

Steal (evict dirty pages of an uncommitted txn) is allowed only if undo/redo can fix it. First file `TableStore` can be **no-steal** (never evict a page that still belongs to an open txn) if that is simpler; say so in the implementation. Do not silently mix steal with “no DML WAL yet” — a crash would lose or corrupt heaps.

---

## Replacement

Small fixed frame count (config later; start with a constant). Clock (second-chance) is enough; LRU is fine too.

Only consider `pinCount == 0`. Prefer clean victims so eviction does not wait on `flush`. If the only victims are dirty, pay WAL-then-write.

This is **not** a Java heap cache (`WeakReference`, `ByteBuffer.allocate` per call). Frames are reused in place so pin can return a stable address for the latch duration.

---

## Who talks to the pool

| Caller | Uses pool? | Why |
|--------|------------|-----|
| File `TableStore` | yes | heap pages |
| `IndexStore` / B+Tree | yes | tree nodes are pages in the same pool |
| Volcano / SQL | no | operators call `TableStore.insert/scan`, never `pin` |
| `LockManager` | no | table/row locks; does not name pages |
| `CatalogManager` / `catalog.json` | no | whole-file rewrite; not paged |
| `WALManager` / `wal.log` | no | sequential append + `flush`; must hit disk **before** data pages |
| `PhysicalStorage` | below the pool | the pool is a client of offset read/write/flush |

One pool for heap and index so a shared clock and a single `CHECKPOINT` flush. Do not give each table its own cache.

---

## Target API (not built)

Sketch only — do not treat as LLD until the interface is filled in.

```text
PageId        file + pageId
BufferFrame   bytes[pageSize] + pinCount + dirty + latch

pin(pageId) -> frame          // load or hit; pinCount++
newPage(file) -> frame        // extend file, pinCount = 1, dirty
unpin(frame)
latchShared(frame) / latchExclusive(frame) / unlatch(frame)
markDirty(frame)
flush(pageId) / flushAll()    // for checkpoint / shutdown
```

`TableStore.insert` (file version): lock table X (LockManager) → pin page with free slot (or `newPage`) → latch X → write slot → markDirty → unlatch → unpin. WAL append for that insert sits **before** the page can be flushed, not necessarily before unpin.

`SELECT` scan: lock table S → for each page: pin → latch S → copy visible slots → unlatch → unpin → next page.

---

## What not to put in the pool

- **Catalog JSON** — not a page array; `CatalogStore` already uses whole-file `read`/`write`.
- **WAL** — if WAL pages lived in the pool, flushing a data page could require flushing WAL which might itself be an unflushed pooled page (cycle). WAL stays a dedicated append path.
- **SQL locks** — do not `lockExclusiveCatalog` around `pin`. Table S/X already decide who may use the table; the latch only protects the bytes.
- **The Java object heap** — `Tuple` copies leave the frame; do not keep a pointer into a frame after unpin (the clock may reuse it).

---

## Build order (this layer only)

| Step | What | Depends on |
|------|------|------------|
| Page layout | header, slot directory, row bytes | `pageSize`; types INT/VARCHAR/BOOLEAN |
| BufferPool | frames, pin/unpin, latch S/X, dirty, clock, flush via `PhysicalStorage` | Page layout; offset I/O (exists) |
| File `TableStore` | insert/scan/update/delete through pin/latch | BufferPool; still dummy Volcano is OK |
| Wire in `StorageEngine` | construct pool, replace `InMemoryTableStore` | above |
| `IndexStore` B+Tree | same pool; crabbing latches | BufferPool |
| DML WAL + WAL-before-flush | page/row records; eviction waits on log | pool + WAL |
| CHECKPOINT flushes dirty pages | extend today’s catalog-only checkpoint | pool |

LockManager table S/X can ship **before** the pool (`docs/todo` step 3 vs 4). Dummy heaps still need table locks; latches appear only when two threads can share a frame.

---

## One line

A page lives on disk; a frame is the RAM copy. Pin keeps the frame; latch makes the bytes safe for this thread; `LockManager` is a different layer and must not name pages. Catalog and WAL stay off the pool. Unlatch and unpin before COMMIT.
