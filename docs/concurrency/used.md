# Where concurrency is used (today)

- `main`: `Thread.currentThread().join()` so the process stays up (accept/workers are daemons).
- `db-shutdown`: JVM shutdown hook thread calls `server.stop()` on Ctrl+C / SIGTERM.
- `db-accept`: one daemon thread; blocking `accept()` so `TcpNetworkModule.start()` can return.
- `db-conn-*`: `java.util.concurrent` cached daemon pool; one worker per open client.
- On each `db-conn-*` thread: receive → `RequestHandler` → `QueryProcessor` → `QueryDispatcher` → `CommandExecutor` → send (same thread, no extra pool).
- `TcpNetworkModule.running`: `AtomicBoolean` for idempotent start/stop and to tell a closed listen socket from a real accept failure.
- Stop: close the listen socket (unblocks `accept`), then `workers.shutdownNow()` + `awaitTermination`.
- Worker names: `AtomicInteger` in the thread factory (`db-conn-1`, `db-conn-2`, …).
- `StorageEngine.running`: `AtomicBoolean` so start/stop are idempotent and `catalogManager()` fails if not started.

## Not a thread pool (names that look like concurrency)

- `QueryDispatcher`: lookup `QueryType` → `QueryExecutor`, then `execute()` on the caller thread.
- `ExecutorRegistry`: map only; no scheduling.
- `LockManager` / `TransactionManager`: empty interfaces; not wired.
