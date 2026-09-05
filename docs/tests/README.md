# Manual tests (client terminal)

SQL you can type in `database-client` against a live `database-server`. **No trailing `;`.**

| Doc | Use for |
|-----|---------|
| [client-manual.md](client-manual.md) | Catalog, DML, PK, indexes, ALTER, CHECKPOINT, restart, two-client extras |
| [transaction-concurrency.md](transaction-concurrency.md) | READ COMMITTED, Strict 2PL, cascadeless reads, deadlock (two clients) |

JUnit stress (`MixedWorkloadLeanStressTest` / `MixedWorkloadHardStressTest`) is **not** a substitute for these; it is concurrent load, not a walkthrough.

## Start (PowerShell)

**Server** (dedicated dir so you can wipe it):

```powershell
mvn -q -DskipTests package
mvn -pl database-server exec:java "-Dexec.args=--port 9090 --data-dir data-test"
```

**Client** (one or two extra terminals):

```powershell
mvn -pl database-client exec:java "-Dexec.args=127.0.0.1 9090"
```

**Script mode** (run a `.txt` of statements, wait for each reply, write a transcript):

```powershell
mvn -pl database-client exec:java "-Dexec.args=--script path\to\commands.txt --out out\run.txt 127.0.0.1 9090"
```

One statement per line; blank lines and `#` / `--` comments are skipped. Optional `--stop-on-error`.

Only one server on port **9090**. After a crash/restart test, start the server again with the **same** `--data-dir`.

## One-shot 1k load + page graph (bash)

From the repo root (Git Bash / WSL / macOS / Linux):

```bash
bash scripts/run_1k_page_graph.sh
```

Wipes `test/data`, starts the server there with `PAGE_SIZE=8192`, runs `input/cmds/load_1k.txt`, `CHECKPOINT`, then writes `out/page-graph/idx_users_name.html`. Override with env vars: `DATA_DIR`, `PORT`, `PAGE_SIZE`.
