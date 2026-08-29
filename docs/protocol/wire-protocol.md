# Wire protocol (v1)

Application-level request/response format for `database-server` ↔ `database-client`.

Transport is unchanged: **length-prefixed TCP frames** (`[4-byte big-endian length][UTF-8 payload]`, max 1 MiB).

## Design choices

| Choice | Why |
|--------|-----|
| **JSON inside the frame** | Easy to debug in a REPL; no extra Maven deps (hand-rolled like `CatalogJson`). |
| **Plain SQL request** | Client sends raw UTF-8 SQL; no session/`USE` because queries use `database.table`. |
| **Typed response messages** | Mirrors real DBs (ERROR, metadata+rows, DONE) without binary TDS/Postgres tokens. |
| **Encoder at network edge** | `QueryProcessor` keeps returning `"OK"` / `"ERROR: …"` strings for unit tests. |
| **Duplicate types in client module** | `database-client` stays a separate app with no server JAR dependency. |

## Request (client → server)

One frame per query batch:

```text
UTF-8 SQL text
```

Example: `CREATE TABLE shop.users (id INT, name VARCHAR)`

## Response (server → client)

One JSON object per frame:

```json
{
  "v": 1,
  "messages": [ ... ]
}
```

Process `messages` **in order** (top to bottom).

### Message types

#### `ERROR`

Query failed. Client prints `message` and stops processing the batch.

```json
{ "type": "ERROR", "message": "ERROR: table already exists: shop.users" }
```

#### `OK`

DDL/DML succeeded with no row stream.

```json
{ "type": "OK", "rowsAffected": 0 }
```

Processor text `"OK"` and unresolved stubs `"OK <query>"` both map here until SELECT returns `RESULT_SET`.

#### `RESULT_SET`

SELECT result (future executor). Column metadata once, then row arrays.

```json
{
  "type": "RESULT_SET",
  "columns": [
    { "name": "id", "type": "INT" },
    { "name": "name", "type": "VARCHAR" }
  ],
  "rows": [
    [1, "alice"],
    [2, null]
  ]
}
```

Cell values: JSON string, number, boolean, or `null`.

#### `DONE`

End of batch; optional footer row count.

```json
{ "type": "DONE", "rowsAffected": 2 }
```

### Example batches

DDL success:

```json
{ "v": 1, "messages": [{ "type": "OK", "rowsAffected": 0 }] }
```

SELECT (future):

```json
{
  "v": 1,
  "messages": [
    {
      "type": "RESULT_SET",
      "columns": [{ "name": "id", "type": "INT" }],
      "rows": [[1], [2]]
    },
    { "type": "DONE", "rowsAffected": 2 }
  ]
}
```

## Code map

| Component | Module |
|-----------|--------|
| `WireResponseEncoder`, `WireResponseJson` (encode) | `database-server` … `network.wire` |
| `JsonWireResponse` | `database-server` … `network.tcp` |
| `DefaultRequestHandler` | maps processor text → JSON frame |
| `WireResponseJson` (parse), `ResponsePrinter` | `database-client` … `wire` |
| `DatabaseClient.executeQuery` | send SQL → parse JSON |
| `Main` | interactive REPL |

## Versioning

Bump `"v"` when message shapes change. Client rejects unknown versions.
