# WAL checkpoint (append-only)

Related: `Phase-2-transaction-lock-wal-plan.md` (Step 3), `DDL-WAL-transactions.md`.

## Status

**Implemented (durable-only DDL).** `wal.log` is **append-only**: checkpoint never empties, truncates, or replaces prior lines.

## What happens on checkpoint

Caller holds exclusive catalog lock (scheduler or `CheckpointExecutor`).

1. `maxTxnId = max(wal.checkpoint, scan wal.log)`
2. Write + flush `wal.checkpoint` `{"maxTxnId":N}`
3. **Append** `{"op":"CHECKPOINT","txnId":N}` to `wal.log` (same append path as DDL flush)
4. Return `maxTxnId`

Does **not** flush dirty pages (no buffer pool yet). Assumes catalog files already reflect committed work through that high-water mark.

## Replay

- Scan whole file for `maxTxnId` (and side-file `wal.checkpoint`)
- Find the **last** `CHECKPOINT` line — that is the recovery fence
- Apply only COMMIT-gated redo **after** that fence (older history stays on disk for audit)

## Triggers

| Path | Mechanism |
|------|-----------|
| Automatic | `CheckpointScheduler` + one plugged `CheckpointStrategy` (`timeout` or `wal_size`) |
| Manual | SQL `CHECKPOINT` → `CheckpointExecutor` (not a strategy) |

Note: with append-only WAL, `max_wal_size` still grows unless you later add segment rotation/deletion of files older than the last checkpoint.
