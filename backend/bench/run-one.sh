#!/usr/bin/env bash
# One self-contained measurement: start one server from the given command, wait for it
# to serve the expected number of graphs, run the backtest against it, and stop exactly
# that server -- and nothing else -- when done.
#
#   run-one.sh <label> <expected-graphs> <server-command...>
#
# Output goes to $RUN_ONE_OUT (default: a fresh temporary directory, printed at the
# end). The server's port is read from $RUN_ONE_PORT (default 18080); the backtest mix
# from $BT_MIX (default: backtest.py's own default).
set -euo pipefail

LABEL=${1:?usage: run-one.sh <label> <expected-graphs> <server-command...>}
EXPECTED=${2:?usage: run-one.sh <label> <expected-graphs> <server-command...>}
shift 2
if [ $# -eq 0 ]; then
  echo "usage: run-one.sh <label> <expected-graphs> <server-command...>" >&2
  exit 2
fi
PORT=${RUN_ONE_PORT:-18080}
OUT=${RUN_ONE_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/graphite-run-one-XXXXXX")}
mkdir -p "${OUT}"
HERE=$(cd "$(dirname "$0")" && pwd)

if curl -s --max-time 3 "http://localhost:${PORT}/api/graphs" >/dev/null 2>&1; then
  echo "FATAL: port ${PORT} is already served; refusing to measure a server this script did not start" >&2
  exit 1
fi

# The server runs in its own process group, so cleanup terminates it and any child it
# spawned, and never a process this script did not create.
setsid "$@" > "${OUT}/server.log" 2>&1 < /dev/null &
SERVER_PID=$!
cleanup() {
  if kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill -TERM -- "-${SERVER_PID}" 2>/dev/null || kill -TERM "${SERVER_PID}" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "${SERVER_PID}" 2>/dev/null || break
      sleep 0.5
    done
    kill -KILL -- "-${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for _ in $(seq 1 240); do
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    echo "FATAL: server exited before serving; see ${OUT}/server.log" >&2
    exit 1
  fi
  GRAPHS=$(curl -s --max-time 5 "http://localhost:${PORT}/api/graphs" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("count", len(d)) if isinstance(d, dict) else len(d))' 2>/dev/null || true)
  [ "${GRAPHS:-}" = "${EXPECTED}" ] && break
  sleep 2
done
if [ "${GRAPHS:-}" != "${EXPECTED}" ]; then
  echo "FATAL: server reports ${GRAPHS:-no} graphs, expected ${EXPECTED}" >&2
  exit 1
fi
echo "server up with ${EXPECTED} graphs"

python3 "${HERE}/backtest.py" --base "http://localhost:${PORT}" --label "${LABEL}" \
  --timeout 60 ${BT_MIX:+--mix "${BT_MIX}"} --out "${OUT}/bt-${LABEL}.json" | grep -vE '^  [0-9]+/'
echo "results: ${OUT}/bt-${LABEL}.json"
