# DDL Phase 1 — Sub-phases

This is the implementation plan for **Phase 1** from `DDL_phases.md`.

Phase 1 is **not** “finish Analyzer, then Planner, then Catalog, then disk.” It is a **CREATE TABLE vertical slice**, then the other DDL statements that only need catalog changes.

```text
Phase 1  (this doc)     CREATE TABLE end-to-end, then DROP / ALTER / INDEX (catalog-only)
Phase 2  (later)        TransactionManager + LockManager + WALManager
```

Do **not** start Phase 2 until `CREATE TABLE` survives process restart through the catalog.

---

## Target pipeline

```text
CREATE TABLE users (id INT, name VARCHAR)
      ↓
Lexer / Parser
      ↓
Analyzer          reads CatalogManager
      ↓
Planner           CreateTablePlan
      ↓
ExecutorService → ExecutorRegistry → CommandExecutor
      ↓
CatalogManager.createTable(...)
      ↓
CatalogStore      JSON → bytes
      ↓
PhysicalStorage   write + flush
```

Done when:

```text
DB starts
   ↓
PhysicalStorage + CatalogStore
   ↓
CatalogManager loads catalog
   ↓
CREATE TABLE users (id INT, name VARCHAR)
   ↓
catalogManager.getTable("users") → correct metadata
   ↓
DB restart
   ↓
catalogManager.getTable("users") → same metadata
   ↓
CREATE TABLE users (...) again → error (already exists)
```

---

## What is already done

| Piece | Status |
| ----- | ------ |
| Network, `DatabaseServer`, `Main` DI | Done. Storage starts before network. |
| `DataDirectory` | Done. Creates store root on `StorageEngine.start()`. |
| `QueryLexer` | Done. |
| `QueryParser` | Done for current DDL **syntax**. |
| `CREATE TABLE` AST | Done, but **column names only** — no types. `CREATE TABLE users (id, name)`. |
| `CREATE DATABASE` / `CREATE INDEX` / `ALTER TABLE ADD\|DROP COLUMN` / `DROP TABLE\|INDEX\|DATABASE` | Parsed only. Not executed. |
| `QueryAnalyser` | Stub. Always returns `true`. No catalog. No resolved AST. |
| `QueryPlanner` | Missing. |
| `ExecutorService` / `ExecutorRegistry` / `CommandExecutor` | Missing. |
| `DefaultQueryProcessor` | Lex → parse → analyse (always ok) → echo `OK <query>`. No plan/execute. |
| `CatalogManager` | Empty interface. Comment lists a planned surface. |
| `CatalogStore` | Missing. |
| `PhysicalStorage` | Empty interface. |
| `TableStore` / `IndexStore` / `BufferPool` | Empty interfaces. **Not Phase 1.** |
| `TransactionManager` / `LockManager` / `WALManager` | Empty interfaces. **Phase 2.** |

Parser already accepts:

```text
CREATE TABLE users (id, name)
ALTER TABLE users ADD age
ALTER TABLE users DROP COLUMN age
DROP TABLE users
CREATE INDEX idx ON users (id)
DROP INDEX idx
```

None of these change disk or catalog today.

---

## Phase 1 constraints

Taken from `Analyser.md`, `Executor.md`, `DDL-Executor.md`, `Physical-storage.md`, `Alter-Column.md`, and the catalog persistence notes.

1. **One statement first:** `CREATE TABLE`. Other DDL waits until that slice reloads after restart.
2. **Analyzer reads catalog. Executor writes catalog.** Analyzer must not call `createTable()`.
3. **DDL is command execution**, not Volcano. Register `CREATE_TABLE` → `CommandExecutor`.
4. **`ExecutorService` orchestrates. `ExecutorRegistry` only looks up** `QueryType → QueryExecutor`.
5. **`CatalogManager` owns logical schema.** It does not know JSON vs binary vs pages.
6. **`CatalogStore` owns catalog persistence.** Serialize metadata, then call `PhysicalStorage`.
7. **`PhysicalStorage` owns bytes/files only.** No SQL, tables, JSON, WAL, or transactions.
8. **Minimum disk, not a storage engine.** Catalog may rewrite a whole metadata file. Do not build `BufferPool`, `.ibd` row pages, or WAL for this phase.
9. **Single database.** Success criterion is `getTable("users")`. No `USE`, no `db.table`, no `CREATE DATABASE` execution in the CREATE TABLE slice.
10. **No transactions / locks / WAL.** Direct catalog update + persist is enough. Phase 2 wraps the same call path.

### Out of Phase 1

```text
TransactionManager, LockManager, WALManager
BufferPool, TableStore row format, IndexStore trees
VolcanoExecutor, SELECT/INSERT/UPDATE/DELETE analysis and planning
Row rewrite on ADD COLUMN
VARCHAR length, NOT NULL, DEFAULT, PRIMARY KEY, UNIQUE
CREATE DATABASE / USE (execution)
```

---

## Ownership

```text
Main
 └── DatabaseServer
      ├── StorageEngine                    owns lifecycle
      │    ├── PhysicalStorage
      │    ├── CatalogStore  → PhysicalStorage
      │    └── CatalogManager → CatalogStore
      │
      └── QueryProcessor                   uses StorageEngine
           ├── Lexer
           ├── Parser
           ├── Analyser     → CatalogManager   (read)
           ├── Planner
           └── ExecutorService
                └── ExecutorRegistry
                     └── CommandExecutor → CatalogManager  (write)
```

`StorageEngine` already starts before the network. Catalog **load** belongs in `StorageEngine.start()`.

---

## Sub-phase map

```text
Phase 1
│
├── 1.1  In-memory catalog
├── 1.2  Minimum PhysicalStorage
├── 1.3  CatalogStore + load on start
│
├── 1.4  CREATE TABLE types in parser
├── 1.5  Analyzer for CREATE TABLE
├── 1.6  Planner for CREATE TABLE
├── 1.7  Command execution path
├── 1.8  End-to-end CREATE TABLE + restart     ← Phase 1 CREATE TABLE is done here
│
├── 1.9  DROP TABLE
├── 1.10 ALTER TABLE ADD COLUMN
└── 1.11 CREATE INDEX / DROP INDEX             ← catalog definitions only
```

`1.1`–`1.3` are the smallest persistence stack `CREATE TABLE` needs. `1.4`–`1.8` are the SQL path. `1.9`–`1.11` reuse that path; they do not invent new storage layers.

Do not “finish Analyzer for every statement” in `1.5`. Only `CREATE TABLE` until `1.8` is green.

---

## 1.1 — In-memory catalog

**Goal:** A `CatalogManager` you can unit-test without SQL or disk.

**Build:**

```text
ColumnType          INT | VARCHAR | BOOLEAN   (minimal; matches existing literals)
ColumnMetadata      columnId, name, type, nullable
TableMetadata       tableId, name, columns
CatalogManager      getTable(name), tableExists(name), createTable(metadata)

DefaultCatalogManager   HashMap in memory, assign tableId/columnId
```

**API shape (Phase 1):**

```text
getTable(name) → TableMetadata | empty
tableExists(name) → boolean
createTable(TableMetadata) → TableMetadata     // fills ids
```

Reject duplicate table names and duplicate column names inside one table.

**Not in 1.1:** `CatalogStore`, files, SQL, databases, indexes, `dropTable`, `addColumn`.

**Done when:** Tests create `users(id INT, name VARCHAR)`, look it up by name, and reject a second `users`. Restart is **not** required yet.

---

## 1.2 — Minimum PhysicalStorage

**Goal:** Generic file bytes under the data directory. Catalog will use this; table pages will use it later.

**Build:**

```text
PhysicalStorage
├── rootDirectory   (DataDirectory.root())
└── pageSize        (configurable; unused by catalog in Phase 1)

+ create(file)
+ delete(file)
+ exists(file)
+ read(file)                  // whole file — enough for catalog JSON
+ write(file, bytes)          // whole file replace — enough for catalog JSON
+ read(file, offset, length)  // keep on the API for future pages
+ write(file, offset, bytes)
+ flush(file)
```

Use an instance (`DefaultPhysicalStorage`), not static helpers.

Whole-file `read`/`write` are the catalog path. Offset APIs exist so we do not paint into a corner; do **not** implement `readPage`/`writePage` until `TableStore` needs them.

**Errors** stay at this layer: file missing, already exists, bad offset, I/O failure. Do not leak raw `IOException` through catalog/SQL.

**Not in 1.2:** JSON, `TableMetadata`, buffering, WAL, `fsync` policy beyond `flush()`.

**Done when:** Tests create a file under a temp `DataDirectory`, write bytes, read them back, flush, delete.

---

## 1.3 — CatalogStore + load on start

**Goal:** Catalog survives `StorageEngine` stop/start. Still no SQL.

```text
TableMetadata
      ↓
CatalogStore serialize (JSON)
      ↓
bytes
      ↓
PhysicalStorage.write(catalog file)
      ↓
PhysicalStorage.flush(...)
```

Load:

```text
PhysicalStorage.read(catalog file)
      ↓
bytes
      ↓
CatalogStore deserialize
      ↓
CatalogManager (memory)
```

**Build:**

```text
CatalogStore
+ load() → in-memory snapshot
+ saveTable(TableMetadata)     // rewrite catalog file is OK
+ saveAll(snapshot)            // used on create

DefaultStorageEngine.start()
      ↓
dataDirectory.ensureExists()
      ↓
physicalStorage
      ↓
catalogStore.load()
      ↓
catalogManager populated
```

**Layout (Phase 1):** one catalog file at the store root, e.g. `data/catalog.json`. Do not implement per-table `metadata/` folders yet. `DataDirectory`’s later `<database>/<table>/data|metadata` layout waits for `TableStore`.

`PhysicalStorage` must not parse JSON. `CatalogManager` must not know the file name or JSON.

Expose catalog from storage so processor/tests can use it:

```text
StorageEngine.catalogManager()
```

**Done when:** A test starts storage, `createTable` in memory, `save`, `stop`, new `StorageEngine` on the same directory, `start`, `getTable("users")` returns the same columns/types.

---

## 1.4 — CREATE TABLE types in the parser

**Goal:** The AST can carry the metadata `1.1` stores.

Today:

```text
CREATE TABLE users (id, name)
CreateTableQuery(table, List<String> columns)
```

Change to:

```text
CREATE TABLE users (id INT, name VARCHAR)
CreateTableQuery
├── table = users
└── columns
    ├── ColumnDefinition(name=id,   type=INT)
    └── ColumnDefinition(name=name, type=VARCHAR)
```

**Lexer:** add type keywords (`INT`, `INTEGER`, `VARCHAR`, `BOOLEAN` as types — `BOOLEAN` already exists for `TRUE`/`FALSE`; decide one token vs two). Keep it small.

**Parser:** require a type after each column name. Names-only `CREATE TABLE users (id, name)` should become a parse error (update parser/processor tests).

**Not in 1.4:** `VARCHAR(255)`, `NOT NULL`, `DEFAULT`, `PRIMARY KEY`. Nullable defaults to `true`.

**Done when:** Parser tests round-trip `CREATE TABLE users (id INT, name VARCHAR)` into typed AST. Old names-only tests are updated.

---

## 1.5 — Analyzer for CREATE TABLE only

**Goal:** Semantic check + resolved CREATE TABLE. No catalog mutation.

```text
CreateTableQuery          syntactic names
      ↓
QueryAnalyser + CatalogManager (read)
      ↓
AnalyzedCreateTable
├── table name
├── columns with types
└── duplicate / exists checks done
```

**Checks:**

```text
table name not already in catalog
≥ 1 column
no duplicate column names
each type is a known ColumnType
```

Analyzer **must not** call `createTable()`.

**API change:** `boolean analyse(ast)` is not enough. Prefer something that can fail with a message and pass a resolved node forward:

```text
analyse(ast) → AnalyzedQuery     // or throw / return error
```

Only implement the `CreateTableQuery` branch. Other AST types may still pass through as “accepted, unresolved” so SELECT/INSERT keep echoing until their own phases. Do not start SELECT name resolution here.

Wire `DefaultQueryAnalyser` to `CatalogManager` (from `StorageEngine`), constructor injection.

**Done when:** Tests with a fake/in-memory catalog reject `CREATE TABLE users (...)` if `users` exists, reject duplicate columns, accept a new typed table, and prove catalog size is unchanged.

---

## 1.6 — Planner for CREATE TABLE

**Goal:** A plan object `CommandExecutor` can execute. No optimization.

```text
AnalyzedCreateTable
      ↓
QueryPlanner
      ↓
CreateTablePlan
├── queryType = CREATE_TABLE
├── table name
└── column metadata (no ids yet — executor/catalog assigns ids)
```

`CreateTablePlan` implements `ExecutionPlan` with `getQueryType()`.

Planner does not talk to `PhysicalStorage`. It does not decide file layout.

**Done when:** A unit test turns analyzed CREATE TABLE into `CreateTablePlan` with the same names/types.

---

## 1.7 — Command execution path

**Goal:** Processor actually creates the table. Persistence uses `1.3`.

```text
DefaultQueryProcessor.execute
      ↓
lex / parse / analyse / plan
      ↓
ExecutorService.execute(plan)
      ↓
ExecutorRegistry.get(CREATE_TABLE) → CommandExecutor
      ↓
CommandExecutor
      ↓
catalogManager.createTable(...)
      ↓
catalogStore persist
```

**Build:**

```text
QueryType            CREATE_TABLE (only this registered for now)
ExecutionPlan
QueryResult          e.g. "OK" / rows later
QueryExecutor        execute(plan) → QueryResult

ExecutorRegistry     register / get
ExecutorService      lookup + execute
CommandExecutor      CREATE_TABLE branch only

DefaultQueryProcessor
  lexer, parser, analyser, planner, executorService, storageEngine
```

`CommandExecutor` is the write side. No `next()` tuple loop. No `VolcanoExecutor` class yet.

**Errors:** table exists should already be caught in `1.5`. Executor may still treat create as a conflict if catalog changed (single-threaded for now).

**Not in 1.7:** locks, WAL, `begin`/`commit`, creating `users.ibd` / table folders.

**Done when:** `processor.execute("CREATE TABLE users (id INT, name VARCHAR)")` returns success **and** `storageEngine.catalogManager().getTable("users")` has those columns. Duplicate CREATE returns an error string, not `OK`. Processor tests that expected a bare `OK CREATE TABLE users (id, name)` must change.

---

## 1.8 — End-to-end CREATE TABLE + restart

**Goal:** The Phase 1 success path from `DDL_phases.md`.

**Test (one integration test is enough):**

```text
start StorageEngine on temp dir
execute CREATE TABLE users (id INT, name VARCHAR)
getTable("users") → id INT, name VARCHAR
stop
start new StorageEngine on same dir
getTable("users") → same
execute CREATE TABLE users (...) → error
```

Optional: same path through TCP (`DatabaseServer`) if cheap; storage-level restart is the requirement.

**Phase 1 CREATE TABLE is complete only when this is green.**

---

## After CREATE TABLE (still Phase 1)

Each statement below is a thin copy of `1.5`–`1.7`: analyse (read) → plan → `CommandExecutor` (write) → `CatalogStore`. No new infrastructure unless the statement truly needs a file delete.

### 1.9 — DROP TABLE

```text
DropTableQuery
      ↓
Analyzer: table must exist → tableId
      ↓
DropTablePlan
      ↓
CommandExecutor
      ↓
catalogManager.dropTable(tableId)
      ↓
CatalogStore persist
```

No `TableStore.dropTable` — there are no row files yet. If `1.3` only has JSON, deleting the catalog entry is enough.

Register `DROP_TABLE` on the same `CommandExecutor` (or a dedicated command class if the switch gets ugly).

**Done when:** DROP removes `getTable("users")`; restart stays gone; DROP missing table is an analysis error.

### 1.10 — ALTER TABLE ADD COLUMN

Follow `Alter-Column.md`, catalog-only:

```text
ALTER TABLE users ADD COLUMN age INT
```

Parser already has ADD/DROP column **names** without types. Extend ADD to require a type (`ADD age INT`), matching `1.4`.

Rules for this phase:

```text
table exists
column name not already present
type is known
nullable = true
default = null
```

Do **not** rewrite rows. There are no rows.

DROP COLUMN can wait, or ship as catalog-only in the same sub-phase if it is equally small. Prefer ADD first.

**Done when:** ADD persists; restart shows the new column; ADD duplicate column fails.

### 1.11 — CREATE INDEX / DROP INDEX (definitions only)

Parser already has `CREATE INDEX idx ON users (id)` and `DROP INDEX idx`.

Store **index definitions** on `TableMetadata` (name, column ids, not unique). Do **not** build `IndexStore` trees or extra files.

Analyzer: table exists, columns exist, index name not duplicated.

**Done when:** definitions round-trip through catalog JSON + restart. Physical index structures are a later DML/index phase.

---

## Suggested order of work

Work **one sub-phase at a time**. After `1.3`, you can test persistence without SQL. After `1.8`, SQL matches disk. Do not start `1.9` until `1.8` is green.

```text
1.1 catalog memory
      ↓
1.2 PhysicalStorage
      ↓
1.3 CatalogStore + StorageEngine.start load
      ↓
1.4 typed CREATE TABLE parse
      ↓
1.5 analyse CREATE TABLE
      ↓
1.6 plan
      ↓
1.7 execute
      ↓
1.8 restart test
      ↓
1.9 DROP TABLE
      ↓
1.10 ADD COLUMN
      ↓
1.11 INDEX definitions
```

`1.4` can overlap `1.1`–`1.3` (parser vs storage). Do not overlap `1.7` with an unfinished `1.3` — execution would have nowhere durable to write.

---

## Processor pipeline: now vs after 1.8

Now:

```text
query → lex → parse → analyse(true) → "OK " + query
```

After 1.8:

```text
query → lex → parse → analyse(resolved) → plan → ExecutorService → QueryResult
```

Lex/parse errors stay as `ERROR at index …`. Analysis/execution errors should be stable strings (`ERROR: table already exists: users`), not stack traces.

---

## What each module is allowed to know

| Module | Knows | Does not know |
| ------ | ----- | ------------- |
| Parser | SQL text → AST | Catalog, files |
| Analyzer | AST + catalog reads | `createTable`, disk |
| Planner | Analyzed DDL → plan | PhysicalStorage |
| CommandExecutor | Plan → catalog writes | JSON, file paths, Volcano |
| CatalogManager | Table/column ids and names | JSON, page size |
| CatalogStore | Metadata ↔ bytes | SQL |
| PhysicalStorage | Files, offsets, bytes | Tables, JSON, SQL |

---

## Related docs

- `docs/temp-dev-notes/DDL_phases.md` — Phase 1 vs Phase 2
- `docs/temp-dev-notes/Analyser.md` — read vs write catalog
- `docs/temp-dev-notes/Executor.md` — registry vs service
- `docs/temp-dev-notes/DDL-Executor.md` — CommandExecutor for DDL
- `docs/temp-dev-notes/Physical-storage.md` — bytes only; catalog may rewrite a whole file
- `docs/temp-dev-notes/Alter-Column.md` — ADD COLUMN is catalog-first; skip row rewrite
- Catalog persistence choice: `CatalogManager` → `CatalogStore` → `PhysicalStorage` (option 4)
