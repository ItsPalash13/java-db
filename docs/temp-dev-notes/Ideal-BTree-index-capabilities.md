# Ideal B+ tree index capabilities — implementation plan

**Status:** Phase 5 baseline is done (equality B+ tree on `.idx`). This doc is the **gap → target** plan for a fuller secondary index.

**Related:** `docs/temp-dev-notes/Page-BufferPool-BTree-plan.md` (Phases 5–7), `docs/product/README.md`, LLD under `docs/lld/`

**Model we keep:** heap `.ibd` + secondary `.idx` (leaf = `key → Rid`). Not InnoDB clustered PK unless explicitly taken up later.

---

## Baseline (what works today)

| Capability | Today |
| ---------- | ----- |
| Equality probe `col = lit` on leading indexed column | `AccessPath.INDEX_SCAN` → `IndexScanOperator` → `lookupEquals` / `lookupRange` |
| Range `>`, `<`, `>=`, `<=` on leading column | `INDEX_SCAN` + `lookupRange` + leaf sibling walk |
| Insert / split / parent promote / new root | `FileIndexStore` |
| Delete merge / borrow (leaf underflow) | `FileIndexStore.rebalanceAfterLeafDelete` |
| Leaf sibling link `nextLeafPageId` | Written on split and sort-build; used by range scans |
| `CREATE INDEX` bulk build from heap | `IndexBuilder` → `IndexSortedBuilder` (sort-build) |
| `CREATE UNIQUE INDEX` + DML enforcement | Parser + `IndexMetadata.unique` + store checks |
| Index page WAL / crash redo | `IndexPageWal` + `PageFlushHook` on buffer pool flush |
| Covering / index-only scan | Always fetch heap by Rid |
| Cost-based choose vs seqscan | Equality heuristic only |

---

## Target capabilities (ideal secondary B+ tree)

### Lookups

1. **Equality** — root → leaf O(height); multi-match same key (non-unique) yields all Rids.
2. **Range scan** — find first leaf for low bound; walk live slots; follow `nextLeafPageId` until high bound fails.
3. **Open-ended range** — `>`, `>=`, `<`, `<=`, and full ordered leaf walk (index-ordered full scan).
4. **Prefix / composite** — equality (or range) on leading column(s) of a multi-column index; trailing columns optional.
5. **Point + filter** — index narrows Rids; residual `WHERE` still applied after heap fetch when needed.

### Structure & maintenance

6. **All payloads in leaves**; internals = separators + child page ids only.
7. **Balanced height** after insert split and delete merge.
8. **Insert split** (have) + **delete merge / redistribute** (need) so occupancy stays sane.
9. **Update key** = delete old `(key, Rid)` + insert new; Rid-only move on growing heap UPDATE (have via maintainer).
10. **Sibling links** kept correct on split **and** merge (prev optional; we can start with next-only).

### Planner / executor

11. **`AccessPathChooser`** picks `INDEX_SCAN` for equality **and** selective ranges on leading indexed columns.
12. **`IndexRangeScanOperator`** (or extend scan op) pulls Rids from leaf chain; then lock + heap fetch like today.
13. **Fallback** to seqscan when no useful index or predicate is not sargable (`OR`, non-leading column only, etc.).
14. Later: **covering** / index-only if projection ⊆ index columns (optional phase).

### Correctness & concurrency

15. **Latches** short-held on index pages (have helpers); never hold page latch until COMMIT.
16. **Row locks** still on heap `rowId` after Rid resolve (RC rules unchanged).
17. **UNIQUE** reject duplicate key on insert (and CREATE INDEX build).
18. Optional later: **gap / next-key** locks for phantoms under RR.

### Lifecycle & durability

19. CREATE / DROP / bulk build (have); optional **sort-then-build** for faster CREATE on large heaps.
20. Index changes durable via **DML WAL** (Phase 6) — same WAL-before-data as heap pages.
21. Recovery redo/undo of index page images or logical index ops.

---

## Implementation phases

Do these **after or interleaved with** Phase 6 (DML WAL) where noted. Prefer range scan before UNIQUE; merge before heavy delete stress; WAL before claiming crash-safe indexes.

### I1 — Index range API + leaf walk

**Goal:** `WHERE id > 990` (and friends) can use the tree, not only the heap.

**Store API**

```text
Iterator<Rid> lookupRange(
    database, table, indexName,
    Object[] lowKey, boolean lowInclusive,
    Object[] highKey, boolean highInclusive   // null high = open end
)
```

- `findLeafPage(lowKey)` (same descent as equality).
- On leaf: start at first slot `cmp(key, low) >= 0` (respect inclusive).
- Emit Rids while `cmp(key, high) <= 0` (or no high).
- If leaf exhausted and still in range → `nextLeafPageId`; stop at `-1`.
- Duplicate keys: emit all matching Rids in leaf order.

**Also:** `lookupEquals` can be implemented as a degenerate range, or keep as fast path that does **not** follow siblings (current behavior).

**Files:** `IndexStore`, `FileIndexStore`, `NoopIndexStore`, tests in `FileIndexStoreTest` (shuffled inserts + range asserts).

### I2 — Planner + Volcano range path

**Goal:** SQL ranges choose the index when useful.

**`AccessPathChooser`**

- Keep equality → `INDEX_SCAN`.
- Add: single comparison `col </>/>=/<= lit` on leading index column → `INDEX_SCAN` (or new `INDEX_RANGE` kind if you want executor clarity).
- Later: simple AND of two bounds on same column (`id >= a` AND `id < b`) once expression AND exists (today: **no AND token** — ranges are one comparison only unless grammar grows).

**Executor**

- Extend `IndexEqualityHelper` → bound extractor, or add `IndexRangeHelper`.
- New `IndexRangeScanOperator`: `open` → `lookupRange`; `next` → Rid → heap fetch + S-lock (copy `IndexScanOperator` protocol).
- Residual Filter only if WHERE has more than the index bounds.

**Tests:** extend `Insert1kIndexStressTest` — assert `INDEX_SCAN`/`INDEX_RANGE` for `id > 990`, row set matches heap filter; log access path.

**Grammar note:** true SQL `BETWEEN` / `AND` needs lexer+parser work; until then, one-sided ranges only.

### I3 — Delete merge / underflow

**Goal:** deletes reclaim space and keep leaf chain dense.

**Policy (locked suggestion)**

- After leaf delete, if live occupancy &lt; ~50% (or &lt; min entries): try **borrow** from sibling; else **merge** into left/right sibling; delete separator from parent; recurse if parent underflows.
- Root with one child → shrink height; update `INDEX_META`.
- Keep `nextLeafPageId` consistent on merge.
- No need for perfect packing; avoid empty leaves left in the chain.

**Files:** `FileIndexStore.delete` path, split helpers mirrored as `mergeLeaf` / `mergeInternal`, latch discipline same as split (unlatch before rewrite neighbors).

**Tests:** insert N, delete most, assert page count / lookup still correct; height can drop.

### I4 — UNIQUE / PRIMARY KEY

**Goal:** reject duplicate indexed keys when declared.

**Catalog**

- `IndexMetadata` flag `unique` (and/or `PRIMARY KEY` → unique + not null later).
- `CREATE UNIQUE INDEX` / `PRIMARY KEY` in parser → analyser → plan → `CommandExecutor`.

**Insert / bulk build**

- Before insert: `lookupEquals`; if any Rid exists → error (unique).
- Bulk build: same check; fail CREATE INDEX if duplicates present.

**Non-unique indexes** stay as today (duplicate keys allowed; Rid disambiguates).

### I5 — Composite / prefix (mostly done, wire planner)

**Have:** multi-column encode in `IndexKeyCodec`; CREATE INDEX `(a, b)`.

**Need:**

- Equality on **leading** column(s) only → index scan with partial key (encode prefix; range or equality on first component).
- Planner: match leading column id list prefix, not only single-column equality.
- Document that `WHERE b = x` alone does **not** use index on `(a,b)`.

### I6 — Sort-build CREATE INDEX (optional)

**Goal:** faster bulk index for large heaps.

- Scan heap → collect `(keyBytes, Rid)` → sort → build leaves left-to-right → build internals bottom-up → set root in meta.
- Alternative: keep incremental insert build (simpler; stress-tested) until size hurts.

### I7 — Covering / index-only (optional, later)

- If SELECT list ⊆ index columns, return values from leaf key decode without heap pin.
- Needs leaf payload or key-only projection path; skip until equality+range are solid.

### I8 — Durability (ties to Phase 6)

- Index page mutations: WAL before dirty `.idx` page may flush/evict.
- CHECKPOINT flushes dirty index frames.
- Crash: redo index pages; undo uses existing logical `IndexInsert`/`IndexDelete` where possible.

### I9 — Concurrency extras (Phase 7+)

- RR: hold S until COMMIT (isolation flag).
- Optional gap locks on index ranges for phantoms.
- Stress: concurrent insert/split + range scan (latch correctness).

---

## Suggested order

```text
I1 range API + leaf sibling walk
  → I2 planner + IndexRangeScanOperator + stress/log asserts
  → I3 merge/underflow
  → I4 UNIQUE
  → I5 composite prefix planning
  → I8 with Phase 6 WAL (or earlier if claiming durable indexes)
  → I6 sort-build / I7 covering / I9 RR-gap as needed
```

Do **not** implement covering or gap locks before range + merge.

---

## File / type touch list

| Area | Types |
| ---- | ----- |
| Store | `IndexStore`, `FileIndexStore`, `NoopIndexStore`, `BTreeLeafPage` (next/prev), merge helpers |
| Plan | `AccessPath` (± `INDEX_RANGE`), `AccessPathChooser`, `SelectPlan` |
| Execute | `IndexRangeHelper`, `IndexRangeScanOperator`, `VolcanoExecutor.scanOperator` |
| Catalog / DDL | `IndexMetadata.unique`, CREATE UNIQUE / PK (I4) |
| Tests | `FileIndexStoreTest`, `Insert1kIndexStressTest`, new range/unique/merge tests |
| Docs | this file; mark Phase 5 “equality only”; update product README when ranges ship; LLD trio on API change |

---

## Locked decisions (unless revisited)

| Topic | Choice |
| ----- | ------ |
| Storage | Secondary `.idx`, leaf `key → Rid` (heap stays source of row bytes) |
| Range direction | Forward via `nextLeafPageId` first; `prev` only if backward scans needed |
| Equality | Keep dedicated `lookupEquals` or thin wrapper over range |
| Unique | Explicit catalog flag; default indexes remain non-unique |
| AND / BETWEEN | Requires grammar; until then one-sided comparisons only for index range |
| Latch vs lock | Page latches microseconds; SQL locks on `rowId` after Rid resolve |
| Clustered PK | Out of scope here (see “Explicitly later” in Page-BufferPool plan) |

---

## Acceptance checks (per capability)

- **Range:** shuffled 1k insert → `WHERE id > 900` uses index path; row multiset equals heap filter; log shows index access path.
- **Open end:** `WHERE id >= 1` via leaf walk returns all keys in **index order** (sorted), not heap insert order — important teaching distinction.
- **Merge:** delete 90% of keys; lookups still correct; no empty leaf left on the sibling chain.
- **Unique:** second insert same key fails; CREATE UNIQUE on duplicate heap fails cleanly.
- **WAL (I8):** kill after commit; index probes match heap after restart.

---

## Non-goals (this doc)

- Hash indexes, bitmap indexes, GiST
- Full cost-based optimizer / statistics
- InnoDB-style clustered primary tree (rows in PK B+)
- Predicate pushdown beyond simple leading-column sargable forms
