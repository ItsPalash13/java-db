# Dev Notes: Server Ownership & DI Wiring

How `DatabaseServer`, `NetworkModule`, `RequestHandler`, and `QueryEngine` are owned and injected. Not a project master doc.

---

## Ownership diagram

```text
Main (composition root)
  │
  ├── QueryEngine          ← DefaultQueryEngine (swap here)
  ├── ServerSocket         ← TcpServerSocket(port)  [created outside network module]
  ├── NetworkModule        ← TcpNetworkModule(serverSocket, queryEngine)
  │         └── owns RequestHandler ← DefaultRequestHandler(queryEngine)
  └── DatabaseServer(networkModule, queryEngine)
            ├── owns NetworkModule
            └── owns QueryEngine
```

---

## Rules in force

1. **`DatabaseServer`** owns `NetworkModule` + `QueryEngine` (constructor injection).
2. **`QueryEngine`** has its own lifecycle: `DatabaseServer.start()` starts the engine **before** the network; `stop()` stops the network **before** the engine.
3. **`NetworkModule` (`TcpNetworkModule`)** owns `RequestHandler`; builds `DefaultRequestHandler` with the injected `QueryEngine`.
4. **`ServerSocket`** is created in `Main` and injected — network module depends on the `ServerSocket` interface, not on constructing TCP itself.
5. **`RequestHandler`** lives under `com.example.database.network.requesthandler`.
6. **`java.net.Socket` / `ServerSocket`** stay inside `network.tcp` implementations.

### Why `TcpServerSocket` is outside the module

Port/CLI bind config stays at the composition root. `TcpNetworkModule` only needs `ServerSocket` (tests can use port `0` or later fakes).

### Why `RequestHandler` is created inside the module

Network owns the handler. Engine is passed in; handler is an owned collaborator, not a `DatabaseServer` field.

### Swapping `QueryEngine`

At startup in `Main` (or tests), pass any `QueryEngine` implementation:

```java
QueryEngine queryEngine = new DefaultQueryEngine(); // or another impl
NetworkModule networkModule = new TcpNetworkModule(serverSocket, queryEngine);
DatabaseServer server = new DatabaseServer(networkModule, queryEngine);
```

Runtime hot-swap is not supported (`final` fields).

---

## Stub status (related)

| Piece | Status |
|---|---|
| Network → RequestHandler | Not wired yet — still echoes `OK <decoded>` |
| `RequestHandler.handle` | Stub — `UnsupportedOperationException` |
| `QueryEngine` | Empty stub |

Intended path: `receive → RequestHandler → QueryEngine → send`  
Actual path: `receive → echo → send`

---

## Related docs

- LLD: `docs/lld/java-database-lld.txt`, `docs/lld/java-database-lld.md`
- Study: `docs/study-report/accept-concurrency.md`, `docs/study-report/accept-thread-daemon.md`
