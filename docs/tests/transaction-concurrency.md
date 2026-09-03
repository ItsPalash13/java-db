# Transaction Concurrency Manual Tests

Manual scenarios for **READ COMMITTED**, **Strict 2PL**, **UndoManager rollback**, and **deadlock detection** (DETECT_RESOLVE). Two interactive clients against a live server.

Catalog / PK / index / ALTER / CHECKPOINT / restart walkthroughs: [client-manual.md](client-manual.md). Start commands: [README.md](README.md).

**Design reference:** `docs/temp-dev-notes/Transaction Concurrency & Recovery Design.md`

**Automated coverage:** `CascadelessReadIntegrationTest`, `ReadCommittedIsolationTest`, `ExplicitTransactionUndoIntegrationTest`, `ExplicitTransactionAbortIntegrationTest`, `ExplicitTransactionLockTest`, `ExplicitTransactionUndoTest`, `DefaultUndoManagerTest`

---



## What Phase 1 proves


| Property                 | Meaning in this codebase                                                                                                   |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| **Strict 2PL on writes** | Row **X** and table **IX** locks are held until `COMMIT` / `ROLLBACK`.                                                     |
| **READ COMMITTED reads** | Row **S** and table **IS** locks are released at **statement end** (`unlockSharedForOwner`).                               |
| **Cascadeless reads**    | A reader blocked on a row S-lock does not see uncommitted values; after writer `ROLLBACK`, reader sees committed data.     |
| **Read-your-writes**     | Same transaction sees its own uncommitted inserts/updates (X-lock bypasses S wait).                                        |
| **Non-repeatable read**  | Allowed: two `SELECT`s in one explicit txn can return different values if another txn commits between them.                |
| **Undo rollback**        | `ROLLBACK` restores heap via per-txn undo log (not full heap snapshot).                                                    |
| **Deadlock abort**       | Circular wait → one txn aborted with error; undo applied; explicit session ended.                                          |
| **Post-lock re-read**    | `SeqScan` / `UpdateOperator` / `DeleteOperator` re-read the row after lock grant so stale scan snapshots are not returned. |


---



## Setup



### Build and start server (one terminal)

```powershell
mvn -q -DskipTests package
mvn -pl database-server exec:java "-Dexec.args=--port 9090 --data-dir data-test"
```

Use a dedicated data directory (`data-test`) so manual runs do not collide with dev data. Only **one** server process may bind port **9090**.

### Start two clients (two more terminals)

```powershell
mvn -pl database-client exec:java "-Dexec.args=127.0.0.1 9090"
```

Label them **Client A** and **Client B**.

### Parser rules

- **No trailing semicolons** — the lexer treats `;` as invalid.
- String comparisons are **case-sensitive** (`'Ada'` ≠ `'ADA'`).
- `OK` on `DELETE` / `UPDATE` means the statement finished; it does **not** mean rows were changed.



### Seed data (run once on either client)

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT, name VARCHAR)
INSERT INTO shop.users VALUES (1, 'Ada')
INSERT INTO shop.users VALUES (2, 'Bob')
```

Verify:

```sql
SELECT * FROM shop.users
```

Expected:

```
id | name
---+-----
1  | Ada
2  | Bob
```



### Reset between scenarios

On one client (both clients should `COMMIT` or `ROLLBACK` any open txn first):

```sql
DELETE FROM shop.users
INSERT INTO shop.users VALUES (1, 'Ada')
INSERT INTO shop.users VALUES (2, 'Bob')
```

Or restart the server with a fresh `--data-dir`.

---



## Scenario 1 — Uncommitted write not visible (cascadeless read)

**Proves:** Strict 2PL + S-lock blocking + post-lock re-read. Reader must not return a value from a scan snapshot taken before the lock; after writer `ROLLBACK`, reader sees **committed** data.


| Step | Client | SQL                                                  | Expected                     |
| ---- | ------ | ---------------------------------------------------- | ---------------------------- |
| 1    | A      | `BEGIN`                                              | `OK`                         |
| 2    | A      | `UPDATE shop.users SET name = 'Hidden' WHERE id = 1` | `OK` — holds X on row 1      |
| 3    | B      | `SELECT name FROM shop.users WHERE id = 1`           | **Blocks** (waits on S-lock) |
| 4    | A      | `ROLLBACK`                                           | `OK` — undo restores `Ada`   |
| 5    | B      | *(query completes)*                                  | `Ada` — not `Hidden`         |


**Failure modes**

- B returns `Hidden` immediately → stale scan snapshot bug (fixed in `SeqScan`).
- B returns `Hidden` after unblock → same bug or old server binary still running.
- B times out (`lock wait timed out after 30s`) → A never released lock (`ROLLBACK` / `COMMIT` missing).

**Automated test:** `CascadelessReadIntegrationTest.readerSeesCommittedValueAfterWriterRollback`

---



## Scenario 2 — DELETE must not remove row after writer rollback

**Proves:** `DeleteOperator` re-reads and re-evaluates `WHERE` after X-lock. A delete that matched only an uncommitted value must not remove the row once the writer rolls back.


| Step | Client | SQL                                                  | Expected              |
| ---- | ------ | ---------------------------------------------------- | --------------------- |
| 1    | A      | `BEGIN`                                              | `OK`                  |
| 2    | A      | `UPDATE shop.users SET name = 'Hidden' WHERE id = 1` | `OK`                  |
| 3    | B      | `DELETE FROM shop.users WHERE name = 'Hidden'`       | **Blocks**, then `OK` |
| 4    | A      | `ROLLBACK`                                           | `OK`                  |
| 5    | B      | *(delete completes)*                                 | Row 1 still exists    |
| 6    | Either | `SELECT name FROM shop.users WHERE id = 1`           | `Ada`                 |


**Automated test:** `CascadelessReadIntegrationTest.deleteDoesNotRemoveRowWhenWhereFailsAfterWriterRollback`

---



## Scenario 3 — Read-your-writes (same client)

**Proves:** Owner with row X-lock can read its own uncommitted insert without blocking on S-lock (read-your-writes in `DefaultLockManager`).


| Step | Client | SQL                                            | Expected   |
| ---- | ------ | ---------------------------------------------- | ---------- |
| 1    | A      | `BEGIN`                                        | `OK`       |
| 2    | A      | `INSERT INTO shop.users VALUES (3, 'Charlie')` | `OK`       |
| 3    | A      | `SELECT name FROM shop.users WHERE id = 3`     | `Charlie`  |
| 4    | A      | `ROLLBACK`                                     | `OK`       |
| 5    | A      | `SELECT name FROM shop.users WHERE id = 3`     | **0 rows** |


**Automated test:** `ReadCommittedIsolationTest.readYourWritesInExplicitTransaction`

---



## Scenario 4 — Non-repeatable read allowed (READ COMMITTED)

**Proves:** S-locks released between statements in an explicit txn, so a second read in the same txn may see another client's committed write. This is **correct** for READ COMMITTED (not REPEATABLE READ).


| Step | Client | SQL                                                   | Expected                          |
| ---- | ------ | ----------------------------------------------------- | --------------------------------- |
| 1    | A      | `BEGIN`                                               | `OK`                              |
| 2    | A      | `SELECT name FROM shop.users WHERE id = 1`            | `Ada`                             |
| 3    | B      | `UPDATE shop.users SET name = 'Updated' WHERE id = 1` | `OK` (auto-commit implicit txn)   |
| 4    | A      | `SELECT name FROM shop.users WHERE id = 1`            | `Updated` (different from step 2) |
| 5    | A      | `ROLLBACK`                                            | `OK`                              |


Reset row 1 to `Ada` afterward if needed.

**Automated test:** `ReadCommittedIsolationTest.nonRepeatableReadAllowedBetweenStatements`

---



## Scenario 5 — Explicit ROLLBACK undoes DML

**Proves:** `UndoManager` restores prior row images on `ROLLBACK` (single client).


| Step | Client | SQL                                                   | Expected                     |
| ---- | ------ | ----------------------------------------------------- | ---------------------------- |
| 1    | A      | `BEGIN`                                               | `OK`                         |
| 2    | A      | `UPDATE shop.users SET name = 'Changed' WHERE id = 2` | `OK`                         |
| 3    | A      | `SELECT name FROM shop.users WHERE id = 2`            | `Changed` (read-your-writes) |
| 4    | A      | `ROLLBACK`                                            | `OK`                         |
| 5    | A      | `SELECT name FROM shop.users WHERE id = 2`            | `Bob`                        |


**Automated test:** `ExplicitTransactionUndoIntegrationTest.rollbackDiscardsExplicitUpdate`

---



## Scenario 6 — Deadlock abort and session cleanup

**Proves:** Wait-for graph detects deadlock, aborts one txn, applies undo, ends explicit session. Survivor's partial work is also rolled back manually in this scenario.


| Step | Client | SQL                                                 | Expected                                   |
| ---- | ------ | --------------------------------------------------- | ------------------------------------------ |
| 1    | A      | `BEGIN`                                             | `OK`                                       |
| 2    | A      | `UPDATE shop.users SET name = 't1-r1' WHERE id = 1` | `OK` — X on row 1                          |
| 3    | B      | `BEGIN`                                             | `OK`                                       |
| 4    | B      | `UPDATE shop.users SET name = 't2-r2' WHERE id = 2` | `OK` — X on row 2                          |
| 5    | B      | `UPDATE shop.users SET name = 't2-r1' WHERE id = 1` | **ERROR** — transaction aborted (deadlock) |
| 6    | B      | `COMMIT`                                            | **ERROR** — no explicit transaction        |
| 7    | A      | `ROLLBACK`                                          | `OK`                                       |
| 8    | Either | `SELECT id, name FROM shop.users ORDER BY id`       | `Ada`, `Bob`                               |


**Automated test:** `ExplicitTransactionAbortIntegrationTest.deadlockAbortRollsBackUpdatesAndEndsExplicitSession`

---



## Scenario 7 — COMMIT makes writes visible to other clients

**Proves:** After X-lock release at commit, other clients see committed values on next statement.


| Step | Client | SQL                                                     | Expected                   |
| ---- | ------ | ------------------------------------------------------- | -------------------------- |
| 1    | A      | `BEGIN`                                                 | `OK`                       |
| 2    | A      | `UPDATE shop.users SET name = 'Committed' WHERE id = 1` | `OK`                       |
| 3    | B      | `SELECT name FROM shop.users WHERE id = 1`              | **Blocks** until A commits |
| 4    | A      | `COMMIT`                                                | `OK`                       |
| 5    | B      | *(query completes)*                                     | `Committed`                |


---



## Scenario 8 — Implicit auto-commit DML (no BEGIN)

**Proves:** Single-statement DML runs inside `runInTransaction`; locks released after statement; change is durable in heap for other clients.


| Step | Client | SQL                                                | Expected                 |
| ---- | ------ | -------------------------------------------------- | ------------------------ |
| 1    | A      | `UPDATE shop.users SET name = 'Solo' WHERE id = 1` | `OK`                     |
| 2    | B      | `SELECT name FROM shop.users WHERE id = 1`         | `Solo` (no blocking txn) |


Reset row 1 after test.

---



## Scenario 9 — Second BEGIN rejected

**Proves:** One explicit transaction per connection session.


| Step | Client | SQL        | Expected                                    |
| ---- | ------ | ---------- | ------------------------------------------- |
| 1    | A      | `BEGIN`    | `OK`                                        |
| 2    | A      | `BEGIN`    | **ERROR** — already in explicit transaction |
| 3    | A      | `ROLLBACK` | `OK`                                        |


**Automated test:** `ExplicitTransactionIntegrationTest.secondBeginWhileInExplicitTransactionFails`

---



## Scenario 10 — Catalog DDL in explicit transaction

**Proves:** DDL in explicit txn is deferred to `COMMIT` (catalog snapshot at `BEGIN`); `ROLLBACK` drops catalog changes.


| Step | Client | SQL                               | Expected                              |
| ---- | ------ | --------------------------------- | ------------------------------------- |
| 1    | A      | `BEGIN`                           | `OK`                                  |
| 2    | A      | `CREATE TABLE shop.temp (id INT)` | `OK`                                  |
| 3    | B      | `SHOW TABLES FROM shop`           | `temp` **not listed** (not committed) |
| 4    | A      | `ROLLBACK`                        | `OK`                                  |
| 5    | B      | `SHOW TABLES FROM shop`           | Still no `temp`                       |


**Automated tests:** `ExplicitTransactionIntegrationTest.beginCreateTwoTablesCommitPersistsBoth`, `beginCreateTwoTablesRollbackPersistsNeither`

---

## Scenario 11 — UPDATE blocks when uncommitted change hides row from snapshot WHERE

**Proves:** `UPDATE`/`DELETE` do not pre-filter on the scan snapshot. Each heap row gets an X-lock attempt before `WHERE` is evaluated, so a writer waits on an uncommitted holder even when snapshot data would fail the predicate (e.g. `WHERE name='Ada'` while heap shows uncommitted `'Hidden'`).

| Step | Client | SQL | Expected |
|------|--------|-----|----------|
| 1 | A | `BEGIN` | `OK` |
| 2 | A | `UPDATE shop.users SET name = 'Hidden' WHERE id = 1` | `OK` — X on row 1 |
| 3 | B | `UPDATE shop.users SET name = 'X' WHERE name = 'Ada'` | **Blocks** on row 1 |
| 4 | A | `ROLLBACK` | `OK` — restores `Ada` |
| 5 | B | *(query completes)* | Updates row to **`X`** (`WHERE name='Ada'` true on live row) |

**Automated test:** `CascadelessReadIntegrationTest.updateWaitsForRowLockWhenSnapshotWhereWouldMiss`

---

## Troubleshooting


| Symptom                                | Likely cause                                                                      |
| -------------------------------------- | --------------------------------------------------------------------------------- |
| `Address already in use: bind`         | Second server on port 9090. Stop the old process first.                           |
| Client shows stale data after fix      | Client connected to old server; rebuild and restart server + clients.             |
| `lock wait timed out after 30s`        | Other client holds X-lock and never `COMMIT`/`ROLLBACK`.                          |
| `transaction aborted (wait-die)`       | Older deadlock mode message; current default is **DETECT_RESOLVE** (graph-based). |
| Duplicate / wrong rows                 | Table not reset between scenarios; run reset SQL or fresh `data-test`.            |
| `ERROR at index … expected IDENTIFIER` | Trailing semicolon or typo (`show.users` vs `shop.users`).                        |


---



## Known limitations

Not bugs — product gaps (see `docs/product/README.md`):

1. **READ COMMITTED only** — non-repeatable read, phantom, write skew are allowed; `REPEATABLE READ` is an enum stub.
2. **Lock wait timeout** — default 30s.
3. **Same-table DDL vs uncommitted DML** — table **X** waits on table **IX**; mixed load can convoy (do not use as a soak test).
4. **Write prefilter** — some `UPDATE`/`DELETE` `WHERE col = literal` paths filter the scan snapshot before locking; scenario 11 covers the wait-on-every-row path.

---



## Quick checklist

Use this when validating isolation on a build:

- [ ] Scenario 1 — reader sees `Ada` after writer rollback
- [ ] Scenario 2 — delete does not remove row after rollback
- [ ] Scenario 3 — read-your-writes in explicit txn
- [ ] Scenario 4 — non-repeatable read between statements
- [ ] Scenario 5 — ROLLBACK restores prior values
- [ ] Scenario 6 — deadlock abort + clean heap
- [ ] Scenario 7 — COMMIT visibility to other client
- [ ] Scenario 11 — UPDATE blocks when snapshot WHERE would miss uncommitted row
- [ ] `mvn -pl database-server test` — all tests pass