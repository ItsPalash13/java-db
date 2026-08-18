# Dev Notes: CatalogManager Persistence Options

How `CatalogManager` relates to disk and `StorageEngine`. Design comparison only — not a project master doc.

Catalog answers **what exists** (databases, tables, columns, indexes). It does not own row data or index structures.

---

## Options

| Option | Design | Pros | Cons |
| ------ | ------ | ---- | ---- |
| **1** | CatalogManager has its **own memory + disk management** | Simplest mental model; fully isolated; catalog can be optimized independently | Duplicates storage infrastructure; separate buffering/WAL/recovery concerns; harder to make catalog + table changes atomic |
| **2** | CatalogManager is separate, but **uses StorageEngine for persistence** | Good separation of logical responsibility vs physical storage; reuses BufferPool/Disk/WAL; easier atomicity | CatalogManager must depend on a storage contract; catalog representation must fit StorageEngine abstraction |
| **3** | CatalogManager is **part of StorageEngine** | Very cohesive if catalog is treated as system metadata; easy coordination with physical storage, WAL, transactions; no awkward boundary | StorageEngine becomes larger; catalog logic can get mixed with physical-storage concerns; harder to independently replace/test catalog logic |
| **4** | CatalogManager is separate, but uses a **dedicated CatalogStore abstraction**, whose implementation uses StorageEngine | Strongest separation: CatalogManager owns catalog logic, CatalogStore owns catalog persistence, StorageEngine owns physical persistence | More components/abstractions; can be overkill for a small DB |

---

## Sketch

```text
Option 1:  CatalogManager ──own──▶ disk (metadata files)
Option 2:  CatalogManager ──uses─▶ StorageEngine ──▶ disk
Option 3:  StorageEngine includes CatalogManager
Option 4:  CatalogManager ──uses─▶ CatalogStore ──uses─▶ StorageEngine ──▶ disk
```

---

## Related docs

- Ownership / DI: `docs/dev-notes/server-ownership-and-di-wiring.md`
- LLD: `docs/lld/java-database-lld.md`, `docs/lld/java-database-lld.txt`
