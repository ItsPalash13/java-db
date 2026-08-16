# Dev Notes: Server Ownership & DI Wiring

How `DatabaseServer`, `NetworkModule`, `RequestHandler`, `QueryEngine`, and `StorageEngine` are owned and injected. Not a project master doc.

---

## Ownership diagram

```text
Main (composition root)
  │
  ├── LaunchConfig         ← port + dataDir from CLI (default ./data)
  ├── DataDirectory        ← store root path (created on StorageEngine.start())
  ├── StorageEngine        ← DefaultStorageEngine(dataDirectory)  [shared]
  ├── QueryEngine          ← DefaultQueryEngine(storageEngine)    [uses storage]
  ├── ServerSocket         ← TcpServerSocket(port)  [created outside network module]
  ├── NetworkModule        ← TcpNetworkModule(serverSocket, queryEngine)
  │         └── owns RequestHandler ← DefaultRequestHandler(queryEngine)
  └── DatabaseServer(storageEngine, networkModule, queryEngine)
            ├── owns StorageEngine
            ├── owns NetworkModule
            └── owns QueryEngine
                      └── uses StorageEngine
```

---

## Rules in force

1. **`DatabaseServer`** owns `StorageEngine` + `NetworkModule` + `QueryEngine` (constructor injection).
2. Lifecycle order: `start()` = storage → query engine → network; `stop()` = network → query engine → storage.
3. **`NetworkModule` (`TcpNetworkModule`)** owns `RequestHandler`; builds `DefaultRequestHandler` with the injected `QueryEngine`.
4. **`ServerSocket`** is created in `Main` and injected — network module depends on the `ServerSocket` interface, not on constructing TCP itself.
5. **`StorageEngine` is shared**: CLI builds it at the composition root; `DatabaseServer` owns its lifecycle. `QueryEngine` (and later other modules) **use** the same instance — they do not start/stop it.
6. **`RequestHandler`** lives under `com.example.database.network.requesthandler`.
7. **`java.net.Socket` / `ServerSocket`** stay inside `network.tcp` implementations.

### Why `TcpServerSocket` is outside the module

Port/CLI bind config stays at the composition root. `TcpNetworkModule` only needs `ServerSocket` (tests can use port `0` or later fakes).

### Why `RequestHandler` is created inside the module

Network owns the handler. Engine is passed in; handler is an owned collaborator, not a `DatabaseServer` field.

### Why storage is not owned by the query engine

Storage can be swapped and used by more than one module. Lifecycle stays on `DatabaseServer`; collaborators only hold a reference for work.

### Swapping `QueryEngine`

At startup in `Main` (or tests), pass any `QueryEngine` implementation:

```java
QueryEngine queryEngine = new DefaultQueryEngine(storageEngine); // or another impl
NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryEngine);
DatabaseServer server = new DatabaseServer(storageEngine, networkModule, queryEngine);
```

Runtime hot-swap is not supported (`final` fields).

---

## Stub status (related)

| Piece | Status |
|---|---|
| Network → RequestHandler | Wired through handler to engine |
| `QueryEngine` | Lex/parse stub; echoes `OK <query>` |
| `StorageEngine` | Holds `DataDirectory` only; creates store root on start |

Intended path: `receive → RequestHandler → QueryEngine → StorageEngine (later) → send`

---

## Related docs

- LLD: `docs/lld/java-database-lld.txt`, `docs/lld/java-database-lld.md`
- Study: `docs/study-report/accept-concurrency.md`, `docs/study-report/accept-thread-daemon.md`
