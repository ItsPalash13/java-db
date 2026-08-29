# WAL checkpoint and truncate

Related: `Phase-2-transaction-lock-wal-plan.md` (Step 3), `DDL-WAL-transactions.md`.

Checkpoint / truncate were **not** in Phase 2 Step 3. That step was append + flush + restart replay. Full-history replay is the known tradeoff of that scope — intentional for learning, not unfinished Step 3.

---

## What we have now

On every `StorageEngine.start()`, if `wal.log` exists, replay reads the **entire** file top→bottom and applies each line if the catalog still needs it.

Correct for crash recovery of DDL; slow and unbounded as the log grows. There is no checkpoint or truncate yet.

---

## Truncate

**Truncate** = drop or shrink the log so old redo is gone.

Typical rule: *only* after those ops are already durable in the catalog (and you don’t need them for recovery).

Example:

1. Catalog on disk already has every CREATE/DROP that was in the WAL
2. Delete `wal.log` or replace it with an empty file
3. Next restart: nothing to replay (or only new ops after that)

Without that guarantee, truncating loses the only copy of “intent that never made it into catalog.json” → data loss on crash.

---

## Checkpoint

A **checkpoint** is a recovery barrier, not a fancy algorithm by itself:

1. Make sure catalog (and later data pages) are **durable** up to some point in the WAL
2. Record that point (e.g. “checkpoint at LSN / file offset / after record N”)
3. Then you may **truncate or ignore** everything **before** that point on replay

Recovery becomes: load catalog → replay only **after** the last checkpoint → not from inception.

Real systems also use checkpoints so recovery doesn’t walk years of history. Variants:

| Approach | Idea |
|----------|------|
| **Truncate after checkpoint** | Delete/compact prefix of WAL once catalog is flushed |
| **Checkpoint record in WAL** | Write `CHECKPOINT` line; replay starts after last one |
| **Segmented WAL** | `wal.0001`, `wal.0002`, … delete old segments after checkpoint |

---

## Why Step 3 skipped it

For a learning DDL WAL, full-file idempotent redo is enough to prove: *intent on disk before catalog mutate → crash → restart fixes catalog*. Compaction is operational scale (restart time, disk growth), not correctness of that proof.

**Replay-all is intentional for now.** Checkpoint/truncate are the next durability-lifecycle piece when those matter — not a bug in Step 3.
