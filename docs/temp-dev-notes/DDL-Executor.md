It **doesn't need to affect your DDL execution model much**.

The Volcano/iterator model is primarily useful for **row-producing DML/DQL execution**. DDL is fundamentally different: it changes database metadata/schema rather than producing a stream of tuples.

### Your execution architecture

For DQL/DML:

```text
ExecutionPlan
      ↓
ExecutorService
      ↓
ExecutorRegistry → VolcanoExecutor
      ↓
Volcano Operator Tree

Project
   ↓
Filter
   ↓
TableScan
   ↓
next() → Tuple
```

For DDL:

```text
ExecutionPlan
      ↓
ExecutorService
      ↓
ExecutorRegistry → CommandExecutor
      ↓
CatalogManager
      ↓
Storage changes if required
```

So I'd separate the **execution model**, not necessarily the entire executor stack.

---

## ExecutorService + ExecutorRegistry

`ExecutorService` is the orchestration entry point. `ExecutorRegistry` only owns lookup/registration:

```text
ExecutorService
│
├── registry: ExecutorRegistry
│      Map<QueryType, QueryExecutor>
│
└── execute(plan)
       ↓
    registry.get(plan.getQueryType())
       ↓
    QueryExecutor.execute(plan)
```

Registered executors:

```text
ExecutorRegistry
│
├── SELECT / INSERT / UPDATE / DELETE
│      └── VolcanoExecutor
│             └── Volcano / iterator model
│
└── CREATE_TABLE / DROP_TABLE / ALTER_TABLE / CREATE_INDEX / DROP_INDEX
       └── CommandExecutor
              └── Command-style execution
```

For example:

```text
CREATE TABLE users (...)
```

becomes:

```text
CreateTablePlan
      ↓
ExecutorService.execute(plan)
      ↓
ExecutorRegistry.get(CREATE_TABLE) → CommandExecutor
      ↓
CommandExecutor.execute(plan)
      ↓
TransactionManager
      ↓
LockManager
      ↓
CatalogManager.createTable(...)
      ↓
CatalogStore
```

There is no meaningful:

```text
next() → row
```

because `CREATE TABLE` doesn't produce rows.

---

## What about `ALTER TABLE`?

Same thing.

```text
ALTER TABLE users ADD COLUMN age INT
```

```text
AlterTablePlan
      ↓
ExecutorService
      ↓
CommandExecutor
      ↓
LockManager.acquire(X)
      ↓
CatalogManager.addColumn(...)
      ↓
CatalogStore
```

No Volcano operator is necessary.

---

## `DROP TABLE`

Same:

```text
DropTablePlan
      ↓
ExecutorService
      ↓
CommandExecutor
      ↓
LockManager
      ↓
CatalogManager.dropTable(...)
      ↓
TableStore / IndexStore
      ↓
CatalogStore
```

Again, command-style execution.

---

## Why not force DDL into Volcano?

Because you'd end up with meaningless abstractions:

```text
CreateTableOperator
   ↓
next()
   ↓
???
```

There isn't a stream of tuples to iterate over.

Volcano is answering:

> **"Give me the next result tuple."**

DDL is answering:

> **"Perform this database state transition."**

Those are fundamentally different execution semantics.

---

### One useful common abstraction

Both executors share the same interface:

```text
QueryExecutor
+ execute(plan: ExecutionPlan): QueryResult
```

but don't force everything to behave identically.

For example:

```text
VolcanoExecutor
+ execute(plan)
    └── VolcanoOperator
          + open()
          + next()
          + close()

CommandExecutor
+ execute(plan)
```

Then:

```text
ExecutorService
    │
    └── ExecutorRegistry
           │
           ├── VolcanoExecutor
           │      ├── Scan
           │      ├── Filter
           │      ├── Join
           │      └── Project
           │
           └── CommandExecutor
                  ├── CreateTable
                  ├── DropTable
                  ├── AlterTable
                  ├── CreateIndex
                  └── DropIndex
```

**So your decision to use Volcano for DML/DQL is actually a good reason to keep DDL separate.** Don't distort DDL just to make every query use the same execution abstraction. The common surface is only `QueryExecutor.execute(plan)`; routing stays in `ExecutorRegistry`, orchestration in `ExecutorService`.
