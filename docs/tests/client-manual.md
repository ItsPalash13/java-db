# Client terminal manual tests

Walkthroughs for one or two REPL clients. Concurrency / isolation scenarios live in [transaction-concurrency.md](transaction-concurrency.md).

**Rules:** no `;` at end of a statement; names are `database.table`; `OK` on `UPDATE`/`DELETE` does not mean rows changed; strings are case-sensitive.

Shared start commands: [README.md](README.md).

---

## A — Catalog (one client)

Fresh `--data-dir` (or `DROP DATABASE` after emptying tables).

```sql
CREATE DATABASE shop
SHOW DATABASES
CREATE TABLE shop.users (id INT, name VARCHAR)
CREATE TABLE shop.orders (id INT, user_id INT, amount INT)
SHOW TABLES FROM shop
DESCRIBE shop.users
DESC shop.orders
```

Expected: `shop` listed; both tables listed; `DESCRIBE` / `DESC` show column names and types.

Errors worth typing:

```sql
CREATE DATABASE shop
CREATE TABLE ghost.t (id INT)
CREATE TABLE shop.users (id INT)
DROP TABLE shop.missing
DROP DATABASE shop
```

Expected: already exists; database does not exist; table already exists; table does not exist; database is not empty (drop tables first).

Cleanup:

```sql
DROP TABLE shop.orders
DROP TABLE shop.users
DROP DATABASE shop
SHOW DATABASES
```

---

## B — DML and types (one client)

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT, name VARCHAR, active BOOLEAN)
INSERT INTO shop.users VALUES (1, 'Ada', TRUE)
INSERT INTO shop.users VALUES (2, 'Bob', FALSE)
INSERT INTO shop.users (id, name) VALUES (3, 'Cara')
SELECT * FROM shop.users
SELECT name FROM shop.users WHERE id = 1
SELECT name FROM shop.users WHERE id > 1
SELECT name FROM shop.users WHERE active = TRUE
UPDATE shop.users SET name = 'Ada Lovelace' WHERE id = 1
DELETE FROM shop.users WHERE id = 2
SELECT * FROM shop.users
```

Expected: three rows after inserts (`Cara` has `active` as `NULL`); equality and `>` work; `TRUE` match is Ada; after delete, Bob is gone; `OK` on update/delete even if you later `WHERE id = 99` (zero rows).

```sql
INSERT INTO shop.users VALUES (1)
SELECT * FROM shop.users WHERE name = 'ada'
```

Expected: value-count error; **0 rows** (`'ada'` ≠ `'Ada Lovelace'`).

---

## C — PRIMARY KEY (one client)

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)
DESCRIBE shop.users
INSERT INTO shop.users VALUES (1, 'Ada')
INSERT INTO shop.users VALUES (1, 'Dup')
INSERT INTO shop.users VALUES (NULL, 'Nope')
SELECT * FROM shop.users
```

Expected: `id` is `Null = NO` on `DESCRIBE`; second insert **ERROR** duplicate key; null PK **ERROR**; one row remains.

```sql
INSERT INTO shop.users VALUES (2, 'Bob')
DELETE FROM shop.users WHERE id = 2
INSERT INTO shop.users VALUES (2, 'Bob2')
SELECT name FROM shop.users WHERE id = 2
```

Expected: reuse of PK after delete works (`Bob2`).

---

## D — Indexes (one client)

```sql
CREATE DATABASE shop
CREATE TABLE shop.orders (id INT PRIMARY KEY, user_id INT, amount INT)
INSERT INTO shop.orders VALUES (1, 10, 100)
INSERT INTO shop.orders VALUES (2, 10, 200)
INSERT INTO shop.orders VALUES (3, 20, 50)
CREATE INDEX idx_orders_user ON shop.orders (user_id)
CREATE UNIQUE INDEX idx_orders_amount ON shop.orders (amount)
SELECT * FROM shop.orders WHERE user_id = 10
SELECT * FROM shop.orders WHERE user_id >= 10
SELECT * FROM shop.orders WHERE amount = 100
INSERT INTO shop.orders VALUES (4, 30, 100)
DROP INDEX idx_orders_amount
INSERT INTO shop.orders VALUES (5, 30, 100)
DROP INDEX idx_orders_user
```

Expected: equality and one-sided range on the **leading** indexed column; unique index rejects duplicate `amount`; after `DROP INDEX` the duplicate `amount` insert succeeds. Results of index scan are in **key order**.

```sql
CREATE INDEX idx_orders_user ON shop.orders (user_id)
CREATE INDEX idx_orders_user ON shop.orders (user_id)
DROP INDEX no_such
```

Expected: already exists; index does not exist.

---

## E — ALTER TABLE (one client)

Catalog-only add: **old rows are not rewritten**; new column reads as `NULL`.

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT, name VARCHAR)
INSERT INTO shop.users VALUES (1, 'Ada')
ALTER TABLE shop.users ADD COLUMN age INT
DESCRIBE shop.users
SELECT * FROM shop.users
INSERT INTO shop.users VALUES (2, 'Bob', 30)
INSERT INTO shop.users (id, name) VALUES (3, 'Cara')
SELECT * FROM shop.users
ALTER TABLE shop.users DROP COLUMN age
SELECT * FROM shop.users
ALTER TABLE shop.users DROP COLUMN name
ALTER TABLE shop.users DROP COLUMN id
```

Expected: after add, Ada’s `age` is null; full `VALUES` needs three fields; column-list insert omits `age` → null; after drop `age`, remaining columns still there; cannot drop last column.

`DROP COLUMN` fails if an **index still references** that column — drop the index first.

---

## F — Transactions (one client)

Full two-client isolation: [transaction-concurrency.md](transaction-concurrency.md).

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)
INSERT INTO shop.users VALUES (1, 'Ada')
BEGIN
UPDATE shop.users SET name = 'Tmp' WHERE id = 1
SELECT name FROM shop.users WHERE id = 1
ROLLBACK
SELECT name FROM shop.users WHERE id = 1
BEGIN
UPDATE shop.users SET name = 'Done' WHERE id = 1
COMMIT
SELECT name FROM shop.users WHERE id = 1
BEGIN
BEGIN
ROLLBACK
COMMIT
ROLLBACK
```

Expected: `Tmp` then `Ada`; after commit `Done`; nested `BEGIN` **ERROR**; `COMMIT`/`ROLLBACK` with no session **ERROR**.

Delete + rollback (PK + index):

```sql
BEGIN
DELETE FROM shop.users WHERE id = 1
SELECT * FROM shop.users
ROLLBACK
SELECT * FROM shop.users
INSERT INTO shop.users VALUES (1, 'Dup')
```

Expected: empty during txn; Ada back after rollback; duplicate PK **ERROR**.

DDL in txn (other client should **not** see the table until commit). `CREATE INDEX` inside `BEGIN` then `ROLLBACK` must delete the `.idx` so a later `CREATE INDEX` with the same name succeeds.

```sql
BEGIN
CREATE TABLE shop.temp (id INT)
ROLLBACK
SHOW TABLES FROM shop
BEGIN
CREATE TABLE shop.temp (id INT)
COMMIT
SHOW TABLES FROM shop
DROP TABLE shop.temp
```

---

## G — CHECKPOINT (one or two clients)

```sql
CHECKPOINT
BEGIN
CHECKPOINT
ROLLBACK
```

Expected: first `OK`; inside `BEGIN` **ERROR** (not allowed in explicit txn).

**Two clients:** A `BEGIN` (leave it open). B `CHECKPOINT` → **ERROR** (other explicit txn active). A `ROLLBACK`. B `CHECKPOINT` → `OK`.

Do **not** mix `CHECKPOINT` with a long uncommitted writer if you want it to finish quickly: checkpoint takes **ENGINE X** and waits on ENGINE **IX**.

---

## H — Restart / durability (one client + restart server)

Keep the same `--data-dir`.

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)
CREATE INDEX idx_users_name ON shop.users (name)
INSERT INTO shop.users VALUES (1, 'Ada')
ALTER TABLE shop.users ADD COLUMN age INT
CHECKPOINT
```

Stop the **server** (Ctrl+C). Start it again on `data-test`. New client:

```sql
SHOW DATABASES
SHOW TABLES FROM shop
DESCRIBE shop.users
SELECT * FROM shop.users
SELECT * FROM shop.users WHERE name = 'Ada'
INSERT INTO shop.users VALUES (1, 'Dup', NULL)
```

Expected: catalog, PK, extra column, heap row, and index lookup survive; duplicate PK still errors.

Without `CHECKPOINT`, committed DML should still come back (WAL redo). If a statement never `COMMIT`ted (explicit txn), it must **not** come back.

Kill the **client** (Ctrl+C) while `BEGIN` + uncommitted `UPDATE` is open; reconnect:

```sql
SELECT name FROM shop.users WHERE id = 1
```

Expected: committed name (session rollback on disconnect).

---

## I — Two clients (besides isolation doc)

Seed on A:

```sql
CREATE DATABASE shop
CREATE TABLE shop.users (id INT PRIMARY KEY, name VARCHAR)
CREATE TABLE shop.orders (id INT PRIMARY KEY, user_id INT)
INSERT INTO shop.users VALUES (1, 'Ada')
INSERT INTO shop.users VALUES (2, 'Bob')
```

| # | What | A | B | Expect |
|---|------|---|---|--------|
| I1 | Two readers | `SELECT * FROM shop.users` | same | Both return immediately |
| I2 | Different rows | `BEGIN` then `UPDATE … WHERE id = 1` | `UPDATE … WHERE id = 2` | B **OK** immediately (different row X) |
| I3 | Same row | leave A in `BEGIN` after update id=1 | `SELECT name FROM shop.users WHERE id = 1` | B **blocks** until A `COMMIT`/`ROLLBACK` |
| I4 | Different tables | `BEGIN` + `INSERT INTO shop.users …` | `INSERT INTO shop.orders VALUES (1, 1)` | Concurrent **OK** |
| I5 | DDL vs writer | `BEGIN` + `INSERT INTO shop.users …` (hold IX) | `ALTER TABLE shop.users ADD COLUMN n INT` | B **waits** (table X vs IX). A `COMMIT` then B finishes, or B times out at 30s |
| I6 | DDL idle table | `COMMIT`/`ROLLBACK` so no open txn | `CREATE INDEX idx_u ON shop.users (name)` | **OK** if nobody holds table IX |
| I7 | CHECKPOINT vs BEGIN | `BEGIN` | `CHECKPOINT` | B **ERROR** refused |
| I8 | Visibility | `BEGIN` + `INSERT … (9, 'Zed')` | `SELECT … WHERE id = 9` | B blocks or 0 rows until A `COMMIT`, then sees `Zed` |

I2 vs I3 is the important lock lesson: **row** X, not “the whole table is exclusive” for DML.

I5 is the table-**X** convoy; do not leave A in `BEGIN` and spam DDL — that is the load the hard stress test skipped.

---

## J — Parser / session errors (one client)

```sql
SELECT * FROM shop.users;
SHOW TABLES
COMMIT
ROLLBACK
```

Expected: `;` **ERROR**; `SHOW TABLES` without `FROM db` **ERROR**; commit/rollback with no txn **ERROR**.

---

## Quick checklist

- [ ] A — create/show/describe/drop
- [ ] B — INT/VARCHAR/BOOLEAN, WHERE `=` / `>`
- [ ] C — PK duplicate + null rejected
- [ ] D — index equality/range + unique + drop
- [ ] E — ADD COLUMN null-pad old rows; DROP COLUMN
- [ ] F — BEGIN/ROLLBACK/COMMIT; nested BEGIN fails
- [ ] G — CHECKPOINT OK; refused inside / beside BEGIN
- [ ] H — restart same data-dir; PK + index + extra column
- [ ] I1–I4 — two clients readers / different rows / same row wait / different tables
- [ ] I5 or I7 — DDL wait or CHECKPOINT refused
- [ ] Isolation pack — [transaction-concurrency.md](transaction-concurrency.md) scenarios 1–7, 11
