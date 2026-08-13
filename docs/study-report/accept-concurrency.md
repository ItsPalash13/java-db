# Study Report: How `ServerSocket.accept()` Works

Context: `TcpNetworkModule` / `TcpServerSocket` in this Java database project, and JDK `java.net.ServerSocket`.

---

## 1. Question

How does `.accept()` work? What happens when nobody is connecting, then someone connects?

## Answer

`accept()` is a **blocking wait** for one new TCP client. It does not busy-poll.

### Call chain in this project

```text
TcpNetworkModule.acceptLoop()
  → serverSocket.accept()                    // our interface
    → TcpServerSocket.accept()
      → java.net.ServerSocket.accept()       // JDK
        → SocketImpl.accept(...)             // native
          → OS accept syscall                // kernel listen backlog
```

### When no one is connecting

1. The `db-accept` thread reaches `serverSocket.accept()`.
2. The call **blocks** inside the OS — the thread sleeps.
3. Almost no CPU is used (not a spin loop).
4. The listen socket was already bound; the kernel holds a **backlog** queue for completed handshakes.
5. The thread stays blocked until:
   - a client completes the TCP handshake, or
   - the listen socket is closed / an error occurs (e.g. `stop()`).

### When someone connects

1. Client opens a TCP connection (`new Socket(host, port)` / `DatabaseClient`).
2. Kernel finishes the handshake and enqueues the connection on the listen backlog.
3. Blocked `accept()` wakes up and returns a `java.net.Socket`.
4. We wrap it as `TcpClientConnection`.
5. Accept loop does `workers.submit(() -> handle(connection))`.
6. Accept thread immediately calls `accept()` again for the next client.
7. The worker handles `receive()` / `send()` for that connection.

```text
no clients:   db-accept ──[blocked in accept()]──► sleeping in kernel

client connects:
  OS wakes accept()
    → TcpClientConnection
    → worker handles client
    → db-accept blocks in accept() again
```

**Mental model:** `accept()` = “give me the next ready connection, or sleep until one exists.”

---

## 2. Question

Explain the same flow with concurrency primitives.

## Answer

| Layer | Primitive | Role |
|---|---|---|
| Our code | `AtomicBoolean` + CAS | start/stop flag (`running`) |
| Our code | `Thread` (`db-accept`) | single acceptor |
| Our code | `ExecutorService` | hand off accepted clients to workers |
| JDK / OS | blocking wait on listen socket | `accept()` parks the thread |
| OS | listen backlog (bounded queue) | completed handshakes waiting to be accepted |
| OS | wake / unblock | connection ready or socket closed |

### Lifecycle — CAS

```text
start(): compareAndSet(false → true) then spawn acceptThread
stop():  compareAndSet(true → false) then close listen socket
```

CAS is lock-free ownership of start/stop. Visibility for later `running.get()` comes from the atomic.

### Conceptual model of `accept()` (NOT real Java source)

```text
// Mental model of kernel behavior — not code in ServerSocket.java
lock(listen_socket)   // or equivalent kernel state
while (backlog is empty AND socket open) {
    wait(listen_socket.condition)   // park this thread
}
conn = dequeue(backlog)
unlock / return conn
```

- Empty backlog → thread **parked** (like `Condition.await()`, but in the kernel).
- New connection → kernel **signals** → acceptor **unparks**, dequeues one connection.

### Hand-off after accept

```text
accept() returns
  → workers.submit(handle(conn))
    → internal work queue (mutex + condition inside the pool)
    → worker thread runs receive/send
```

Pattern: **1 producer** (acceptor) + **N consumers** (workers).

### Shutdown wake-up

Closing the listen socket is what unblocks `accept()` (throws `IOException`).  
`Thread.interrupt()` alone is often not enough for a platform-thread blocked in `accept()`.

```text
stop():
  CAS running true→false
  close(listen)            // UNPARK accept with error
  shutdownNow(workers)
  awaitTermination(...)    // timed join
```

---

## 3. Question

Where is the `lock` / `while` / `wait` / `dequeue` code?

## Answer

**Not in this project, and not as Java in `ServerSocket.accept()`.**

It was explanatory pseudocode for kernel behavior.

Real stack:

```text
Our code
  TcpServerSocket.accept()
    → java.net.ServerSocket.accept()     // JDK Java
      → native accept0 / NET_Accept      // HotSpot C
        → accept() / AcceptEx            // OS syscall
          → kernel TCP listen backlog    // wait + dequeue here
```

You will not find `lock(listen_socket)` or `wait(listen_socket.condition)` in `JavaDatabase` sources.

---

## 4. Question

Read JDK `java.net.ServerSocket` and explain `accept`.

## Answer

### Public `accept()`

```java
public Socket accept() throws IOException {
    if (isClosed())
        throw new SocketException("Socket is closed");
    if (!isBound())
        throw new SocketException("Socket is not bound yet");
    Socket s = new Socket((SocketImpl) null);  // empty shell
    implAccept(s);                             // attach real connection
    return s;
}
```

Javadoc: *“The method blocks until a connection is made.”*  
There is no Java `while (backlog empty) wait(...)` in this method.

### What `implAccept` does (normal platform path)

1. Blank `Socket` has `impl == null`.
2. Create a **new** platform `SocketImpl` for the client connection.
3. Call `impl.accept(si)` on the **server’s** listen `SocketImpl`.
4. Attach that new impl to the returned `Socket` via `setConnectedImpl`.

Important: the server keeps its listen `SocketImpl`; the accepted client gets a **separate** `SocketImpl`.

### Where blocking happens in JDK Java

```java
private void implAccept(SocketImpl si) throws IOException {
    impl.accept(si);   // leaves Java → native → OS
}
```

### Bind / backlog (set up before accept)

```java
impl.bind(...);
impl.listen(backlog);  // default backlog often 50
bound = true;
```

`listen(backlog)` asks the OS to queue up to ~N completed handshakes.  
`accept` dequeues one; if empty, the syscall blocks.

### Interrupt / close (from JDK docs + code)

| Situation | Effect |
|---|---|
| Another thread `close()`s the server socket | Blocked `accept` fails with `SocketException` |
| `SO_TIMEOUT` set | May throw `SocketTimeoutException` |
| Virtual thread + interrupt (system-default impl) | May close socket and throw `SocketException` |
| NIO channel + interrupt | `ClosedByInterruptException` |

That is why `TcpNetworkModule.stop()` closes the listen socket.

---

## 5. Question

Is it using `BlockingQueue` or `ReentrantLock`?

## Answer

**No** — not for waiting on clients inside `ServerSocket.accept()`.

| Piece | Mechanism |
|---|---|
| Wait for a connection | OS `accept` syscall |
| Pending connections | Kernel listen backlog (not `BlockingQueue`) |
| Create/close in `ServerSocket` | `synchronized (socketLock)` on a plain `Object` |
| Our worker hand-off | `ExecutorService.submit` (pool may use queues/locks **after** accept) |

```text
accept() → impl.accept(si) → native → kernel accept
```

`ReentrantLock` / `BlockingQueue` coordinate **Java threads**.  
`accept` waits for the **network stack**.

---

## 6. Question

Why the kernel? Why not Java concurrency primitives?

## Answer

Because the event you wait for is **not produced by a Java thread**.

### What “client connected” really means

1. Packets arrive on the NIC  
2. Kernel TCP stack completes the handshake  
3. Kernel enqueues the connection on the listen backlog  
4. Only then can user code take it via `accept`

Java cannot see the NIC or TCP state machine. Only the kernel can.

### Different waits

| Primitive | Waits for |
|---|---|
| `ReentrantLock` / `Condition` | another Java thread to `signal()` |
| `BlockingQueue.take()` | another Java thread to `put()` |
| `Object.wait()` | another Java thread to `notify()` |
| `accept()` syscall | kernel / network event |

Nobody in the JVM can `put()` a connection onto a queue **before** the OS has finished the handshake. There is no Java thread that receives SYN packets.

```text
BlockingQueue   = wait for a Java producer
accept()        = wait for the kernel / network
```

### Can Java still use a queue?

Yes — **after** accept:

```text
kernel accept → Java wakes → optional queue/pool submit → workers
```

Our `workers.submit(...)` is that second stage. The **first** wait must still be kernel `accept` (or NIO `Selector` / epoll — also kernel).

### Why the kernel must own it

- Isolation from raw hardware  
- Correct TCP (handshake, backlog, security)  
- Multiplexing many sockets/processes  
- Efficient sleep/wake on packet arrival (no Java busy-loop)

**One line:** Java primitives coordinate Java threads; `accept` coordinates with the network stack, which lives in the kernel.

---

## Summary diagram

```text
[Client TCP connect]
        │
        ▼
[Kernel listen backlog]  ◄── listen(backlog) at bind time
        │
        │  accept syscall (block if empty)
        ▼
[java.net.ServerSocket.accept]
        │
        ▼
[TcpServerSocket → TcpClientConnection]
        │
        │  workers.submit (Java pool / queue)
        ▼
[handle: receive / send]
```

## Project files involved

- `database-server/.../tcp/TcpNetworkModule.java` — accept loop + workers  
- `database-server/.../tcp/TcpServerSocket.java` — wraps JDK `ServerSocket.accept()`  
- JDK `java.net.ServerSocket` — Java setup; native/OS does the real wait  
