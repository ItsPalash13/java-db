# Dev Notes: Server Ownership & DI Wiring

How `DatabaseServer`, `NetworkModule`, `RequestHandler`, `QueryProcessor`, and `StorageEngine` are owned and injected. Not a project master doc.

---

## Ownership diagram

```text
Main (composition root)
  │
  ├── LaunchConfig         ← port + dataDir from CLI (default ./data)
  ├── DataDirectory        ← store root path (created on StorageEngine.start())
  ├── StorageEngine        ← DefaultStorageEngine(dataDirectory)  [shared]
  ├── QueryProcessor       ← DefaultQueryProcessor(storageEngine) [uses storage; no lifecycle]
  ├── ServerSocket         ← TcpServerSocket(port)  [created outside network module]
  ├── NetworkModule        ← TcpNetworkModule(serverSocket, queryProcessor)
  │         └── owns RequestHandler ← DefaultRequestHandler(queryProcessor)
  └── DatabaseServer(storageEngine, networkModule, queryProcessor)
            ├── owns StorageEngine
            ├── owns NetworkModule
            └── uses QueryProcessor
```

---

## Rules in force

1. **`DatabaseServer`** owns `StorageEngine` + `NetworkModule` lifecycles (constructor injection). It **uses** `QueryProcessor` (no start/stop).
2. Lifecycle order: `start()` = storage → network; `stop()` = network → storage.
3. **`NetworkModule` (`TcpNetworkModule`)** owns `RequestHandler`; builds `DefaultRequestHandler` with the injected `QueryProcessor`.
4. **`ServerSocket`** is created in `Main` and injected — network module depends on the `ServerSocket` interface, not on constructing TCP itself.
5. **`StorageEngine` is shared**: CLI builds it at the composition root; `DatabaseServer` owns its lifecycle. `QueryProcessor` (and later other modules) **use** the same instance — they do not start/stop it.
6. **`QueryProcessor` has no lifecycle** — it is a stateless-style collaborator used when requests arrive.
7. **`RequestHandler`** lives under `com.example.database.network.requesthandler`.
8. **`java.net.Socket` / `ServerSocket`** stay inside `network.tcp` implementations.

### Why `TcpServerSocket` is outside the module

Port/CLI bind config stays at the composition root. `TcpNetworkModule` only needs `ServerSocket` (tests can use port `0` or later fakes).

### Why `RequestHandler` is created inside the module

Network owns the handler. Processor is passed in; handler is an owned collaborator, not a `DatabaseServer` field.

### Why storage is not owned by the query processor

Storage can be swapped and used by more than one module. Lifecycle stays on `DatabaseServer`; collaborators only hold a reference for work.

### Swapping `QueryProcessor`

At startup in `Main` (or tests), pass any `QueryProcessor` implementation:

```java
QueryProcessor queryProcessor = new DefaultQueryProcessor(storageEngine); // or another impl
NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryProcessor);
DatabaseServer server = new DatabaseServer(storageEngine, networkModule, queryProcessor);
```

Runtime hot-swap is not supported (`final` fields).

---

## Stub status (related)

| Piece | Status |
|---|---|
| Network → RequestHandler | Wired through handler to processor |
| `QueryProcessor` | Lex/parse stub; echoes `OK <query>`; no lifecycle |
| `StorageEngine` | Holds `DataDirectory` only; creates store root on start |

Intended path: `receive → RequestHandler → QueryProcessor → StorageEngine (later) → send`

---

## Related docs

- LLD: `docs/lld/java-database-lld.txt`, `docs/lld/java-database-lld.md`
- Study: `docs/study-report/accept-concurrency.md`, `docs/study-report/accept-thread-daemon.md`
