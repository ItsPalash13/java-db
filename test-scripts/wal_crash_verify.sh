#!/usr/bin/env bash
# Force-kill WAL recovery check: load churn → kill -9 → restart → SELECT == expected.
#
# Why kill -9 (not Ctrl+C)? Shutdown hooks call storage.stop() → flushAll(), which is a
# clean shutdown. kill -9 skips hooks so dirty pages stay unflushed; WAL must redo the tail.
#
# Prefer MSYS2 bash on Windows (not WSL): 
#   C:\msys64\usr\bin\bash.exe scripts/wal_crash_verify.sh
#
# Env: DATA_DIR PORT HOST SEED JAVA_HOME
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DATA_DIR="${DATA_DIR:-test/wal-crash}"
PORT="${PORT:-9091}"
HOST="${HOST:-127.0.0.1}"
SEED="${SEED:-42}"
OUT_DIR="${OUT_DIR:-out/wal-crash}"
EXPECTED="${OUT_DIR}/expected.tsv"
SERVER_PID=""

cleanup() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

wait_for_port() {
  local host="$1" port="$2" tries="${3:-60}"
  local i
  for ((i = 1; i <= tries; i++)); do
    if (echo >/dev/tcp/"${host}"/"${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "timed out waiting for ${host}:${port}" >&2
  return 1
}

free_port() {
  local port="$1"
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command \
      "\$c = Get-NetTCPConnection -LocalPort ${port} -ErrorAction SilentlyContinue; if (\$c) { \$c | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force -ErrorAction SilentlyContinue } }" \
      >/dev/null 2>&1 || true
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti ":${port}" | xargs -r kill -9 2>/dev/null || true
  fi
  sleep 1
}

start_server() {
  echo "==> start server --data-dir ${DATA_DIR} --port ${PORT}"
  mvn -pl database-server exec:java \
    "-Dexec.args=--port ${PORT} --data-dir ${DATA_DIR}" &
  SERVER_PID=$!
  wait_for_port "${HOST}" "${PORT}"
}

run_harness() {
  local mode="$1"
  # client.mainClass (pom property) — exec.mainClass CLI does not override a hard-coded pom mainClass
  mvn -q -pl database-client exec:java \
    -Dclient.mainClass=com.example.client.WalCrashHarness \
    -Dexec.interactive=false \
    "-Dexec.args=${mode} ${HOST} ${PORT} ${SEED} ${EXPECTED}"
}

echo "==> free port ${PORT}"
free_port "${PORT}"

echo "==> wipe ${DATA_DIR}"
rm -rf "${DATA_DIR}"
mkdir -p "${DATA_DIR}" "${OUT_DIR}"

echo "==> mvn package"
mvn -q -DskipTests package

start_server

echo "==> load churn (seed=${SEED}) — leave dirty pages in server RAM"
run_harness load

echo "==> force-kill server pid=${SERVER_PID} (no flushAll)"
kill -9 "${SERVER_PID}" 2>/dev/null || true
wait "${SERVER_PID}" 2>/dev/null || true
SERVER_PID=""
sleep 1
free_port "${PORT}"

start_server

echo "==> verify SELECT == expected after recovery"
run_harness verify

echo "==> done OK"
echo "    data:     ${DATA_DIR}"
echo "    expected: ${EXPECTED}"
echo "    meta:     ${OUT_DIR}/meta.txt"
echo "    seed:     ${SEED}"
