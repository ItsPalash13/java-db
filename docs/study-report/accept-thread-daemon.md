# Study Report: Why the Accept Thread Is Separate (and Daemon)

Context: `TcpNetworkModule.start()` creates `db-accept` with `setDaemon(true)`.

---

## 1. Question

Why is the accept thread a daemon?

## Answer

A **daemon** thread does not keep the JVM alive by itself. When only daemon threads remain, the JVM exits.

```java
acceptThread = new Thread(this::acceptLoop, "db-accept");
acceptThread.setDaemon(true);
acceptThread.start();
```

`db-accept` blocks forever in `accept()`. If it were **non-daemon** and nothing else kept the process up, the JVM could stay alive only because that accept loop exists.

Making it daemon means: **this listener should not decide process lifetime**. Lifetime is owned by whoever started the server (today: `Main`).

In `Main`:

```java
Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "db-shutdown"));
server.start();
Thread.currentThread().join();
```

- `main` calls `join()` on itself → process stays up on purpose  
- Ctrl+C → shutdown hook → `server.stop()` → close listen socket / shut down workers  
- Accept/worker threads being daemon is a safety net so they don’t pin the JVM if ownership changes  

Worker threads are also daemon (`namedFactory` sets `setDaemon(true)`).

### Daemon vs non-daemon

| | Daemon accept thread | Non-daemon accept thread |
|---|---|---|
| Keeps JVM alive alone? | No | Yes |
| Typical role | Background helper | Can own server lifetime |
| Current `Main` | Fine (`join()` keeps process up) | Also fine |

### Caveat

Daemon is **not** a substitute for clean shutdown. On hard JVM exit, daemon threads can be killed mid-work. That is why `stop()` + the shutdown hook still close sockets and shut down the pool.

**Short answer:** so the accept loop won’t keep the JVM running by itself; process lifetime stays with `Main` / explicit `stop()`.

---

## 2. Question

Why does it need to be “background”?

## Answer

You need a **separate** accept thread because of how `start()` is designed — not because TCP requires a mysterious “background” mode.

### What would happen on the caller thread

`accept()` **blocks**. If the accept loop ran on `main` / the caller of `start()`:

```text
server.start()
  → acceptLoop()
    → accept()   // blocks forever here
```

then `start()` would **never return**.

But the API is meant to be:

```text
start()  → begin listening, return
stop()   → shut down
```

So something else must sit in `accept()`. That is the `db-accept` thread.

### Two different meanings of “background”

**1. Separate thread (required for this API)**  
Accept cannot share the caller’s thread if `start()` must return. The accept loop is long-running and blocking, so it gets its own thread.

**2. Daemon flag (optional)**  
`setDaemon(true)` only says: “don’t keep the JVM alive by yourself.”  
You do **not** need daemon for correctness. A non-daemon accept thread also works with the current `Main` (`join()` already keeps the process up).

### Alternative design

Many servers do:

```text
main thread = accept loop
```

and never return from `start()`. That is also valid.

This project chose:

```text
caller thread: start/stop control
db-accept:     blocked in accept()
workers:       handle each client
```

so the network module can start/stop without trapping the caller forever inside `start()`.

**Short answer:** separate thread so `start()` can return while `accept()` still blocks. Daemon only marks that thread as non–lifetime-owning; the separate thread is the real need.

---

## Summary

```text
Main / DatabaseServer.start()
        │  returns quickly
        ▼
   db-accept (separate thread, daemon)
        │
        └── blocked in accept()
                │
                └── workers.submit(handle)  (also daemon workers)
```

| Choice | Required? | Why |
|---|---|---|
| Separate accept thread | Yes, for this `start()`/`stop()` API | `accept()` blocks; caller must return |
| `setDaemon(true)` | No, style/safety | Don’t let accept/workers alone pin the JVM |

## Project files

- `database-server/.../tcp/TcpNetworkModule.java` — creates daemon `db-accept`  
- `database-server/.../Main.java` — owns process lifetime via `join()` + shutdown hook  
