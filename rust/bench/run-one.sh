#!/usr/bin/env bash
# One self-contained measurement: clean the box, start one server, probe, benchmark,
# stop it, and say so unambiguously. Written after two runs were silently measuring a
# server left behind by an earlier one, and a third never started because a stray
# non-zero exit killed the command that was meant to launch it.
set -u
S=/tmp/claude-0/-home-user-graphite/ddd6f106-81ab-5135-8d4b-322b738e7c9d/scratchpad
OUT=${1:?usage: bench.sh <label>}

cleanup() {
  pgrep -f 'graphite-explore.jar' | xargs -r kill -9 2>/dev/null
  pgrep -f 'release/graphite-explore' | xargs -r kill -9 2>/dev/null
  sleep 4
}
trap cleanup EXIT

cleanup
if curl -s --max-time 3 http://localhost:18080/api/graphs >/dev/null 2>&1; then
  echo "FATAL: port 18080 still served after cleanup"; exit 1
fi

nohup setsid bash "$S/run-rust.sh" > "$S/rust.log" 2>&1 < /dev/null &
for _ in $(seq 1 120); do
  curl -s --max-time 5 http://localhost:18080/api/graphs >/dev/null 2>&1 && break
  sleep 5
done
graphs=$(curl -s --max-time 10 http://localhost:18080/api/graphs \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d if isinstance(d,list) else d.get("graphs",[])))' 2>/dev/null)
if [ "${graphs:-0}" != "64" ]; then
  echo "FATAL: server reports ${graphs:-none} graphs, expected 64"; exit 1
fi
echo "server up with 64 graphs"

cd /home/user/graphite/rust/bench || exit 1
python3 - <<'PY'
import http.client, json, statistics, time
c = http.client.HTTPConnection("localhost", 18080, timeout=120)
def post(q, n=11):
    out = []
    for _ in range(n):
        b = json.dumps({"query": q}).encode()
        t = time.perf_counter()
        c.request("POST", "/api/cypher", body=b,
                  headers={"Content-Type": "application/json", "Connection": "keep-alive"})
        r = c.getresponse(); r.read()
        out.append((time.perf_counter() - t) * 1000)
    return statistics.median(out)
FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
def wide(t):
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'OR n.caller_name CONTAINS "{t}" OR n.callee_name CONTAINS "{t}" '
            f'RETURN n.graphId, {FOUR} LIMIT 200')
post("RETURN 1", 15)
print(f"  {'floor':12s} {post('RETURN 1'):7.3f}ms", flush=True)
for t in ["zzqqxxvv", "Spooler", "Activity", "Object"]:
    print(f"  {t:12s} {post(wide(t)):7.3f}ms", flush=True)
PY

python3 backtest.py --base http://localhost:18080 --label rust --timeout 60 \
  --out "$S/bt-$OUT.json" | grep -vE '^  [0-9]+/'
echo "BENCHDONE"
