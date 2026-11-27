#!/usr/bin/env bash
# Replays the PR #229 acceptance evidence for a baseline and a candidate revision.
#
#   replay.sh <base-rev> <base-graphite> <base-graphite.jar> \
#             <cand-rev> <cand-graphite> <cand-graphite.jar> \
#             <input.jar> <out-dir>
#
# <rev> labels each revision (a commit). <graphite> is that revision's `cargo build -p
# graphite-cli` binary; <graphite.jar> its `./gradlew :query:shadowJar` jar. <input.jar> is
# the bytecode every graph is built from (the evidence used frontend/jvm/core's jar). The
# acme fixture is compiled from backend/bench/fixtures/acme/src of the checkout this script
# lives in, with `javac --release 17`. Every graph is built by its own revision's frontend.
# Output: one file per reproducer in <out-dir>, each with the identities it ran on.
# Needs: bash, python3, java/javac/jar/jshell 17+, curl, sha256sum.
#
# Exit status: 0 only when every step ran. A setup step that fails (compiling the fixture,
# building or unpacking a graph, a server that does not start), a candidate whose CLI
# differential or completeness check fails, or a candidate bit flip that ends in a panic,
# a crash or an unexpected exit code ends the replay with 1, and the step logs are kept in
# <out-dir>/replay-logs. The baseline's failures are the evidence and are only recorded.
set -euo pipefail
[ $# -eq 8 ] || { sed -n '2,19p' "$0"; exit 2; }
BASE_REV=$1 BASE_BIN=$(realpath "$2") BASE_JAR=$(realpath "$3")
CAND_REV=$4 CAND_BIN=$(realpath "$5") CAND_JAR=$(realpath "$6")
INPUT=$(realpath "$7") OUT=$(realpath -m "$8")
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
WORK=$(mktemp -d)
LOGS="$OUT/replay-logs"
mkdir -p "$OUT"
rm -rf "$LOGS"; mkdir -p "$LOGS"
SERVER=""
finish() {
  status=$?
  [ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null || true
  rm -rf "$WORK"
  if [ $status -eq 0 ]; then rm -rf "$LOGS"; else echo "replay failed (exit $status); step logs in $LOGS" >&2; fi
}
trap finish EXIT
# Runs a setup step, its output kept in $LOGS/<name>.log; a failure ends the replay.
step() {
  local name=$1; shift
  if ! "$@" > "$LOGS/$name.log" 2>&1; then
    echo "replay: step '$name' failed: $*" >&2
    tail -20 "$LOGS/$name.log" >&2
    exit 1
  fi
}

rev_bin() { [ "$1" = base ] && echo "$BASE_BIN" || echo "$CAND_BIN"; }
rev_jar() { [ "$1" = base ] && echo "$BASE_JAR" || echo "$CAND_JAR"; }
rev_id() { [ "$1" = base ] && echo "baseline $BASE_REV" || echo "candidate $CAND_REV"; }
quiet() { grep -v '^Picked up ' || true; }
# Runs a command whose outcome is evidence: its output, and its exit code when it fails,
# are recorded and the replay goes on.
evidence() { local code=0; "$@" 2>&1 || code=$?; [ $code -eq 0 ] || echo "exit=$code"; }
count_backward() { ls "$1" | grep -c '^backward\.' || true; }

# Identities.
mkdir -p "$WORK/acme-classes"
step javac-acme javac --release 17 -d "$WORK/acme-classes" $(find "$REPO/backend/bench/fixtures/acme/src" -name '*.java')
step jar-acme jar cfe "$WORK/acme.jar" com.acme.shop.api.Main -C "$WORK/acme-classes" com
{
  echo "# Identities of this replay"
  echo "baseline revision:  $BASE_REV"
  echo "candidate revision: $CAND_REV"
  sha256sum "$BASE_BIN" "$BASE_JAR" "$CAND_BIN" "$CAND_JAR" "$INPUT" "$WORK/acme.jar" | sed "s|$WORK/||"
  echo "acme sources: $(cd "$REPO" && git rev-parse HEAD 2>/dev/null) backend/bench/fixtures/acme/src"
  { java -version 2>&1 || true; } | quiet | head -1
  javac -version 2>&1 | quiet
  uname -srm
} > "$OUT/identities.txt"

# Graphs, built by each revision's own frontend.
build() { # rev input include name
  local dir="$WORK/$1/$4"
  mkdir -p "$WORK/$1"
  step "build-$1-$4" env GRAPHITE_FRONTEND_JVM="$(rev_jar "$1")" "$(rev_bin "$1")" build "$2" -o "$dir" --include "$3"
  step "unpack-$1-$4" "$(rev_bin "$1")" unpack "$dir.graphite" "$dir"
  rm -rf "$dir/META-INF"
}
for rev in base cand; do
  build $rev "$INPUT" io.johnsonlee.graphite fixture-graph
  build $rev "$WORK/acme.jar" com.acme acme-graph
done

# Truncation: a file cut short, the edge histogram it answers.
{
  echo "# Truncated graph files: what a query answers (identities.txt)"
  Q="MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t"
  for rev in base cand; do
    echo "### $(rev_id $rev): undamaged"; "$(rev_bin $rev)" query "$WORK/$rev/fixture-graph" "$Q" -f csv
  done
  for cut in "graph.labels 1" "graph.nodedata 64" "forward.graph 16"; do
    set -- $cut
    for rev in base cand; do
      rm -rf "$WORK/trunc"; cp -r "$WORK/$rev/fixture-graph" "$WORK/trunc"
      python3 -c 'import os,sys; p=sys.argv[1]; os.truncate(p, os.path.getsize(p)-int(sys.argv[2]))' "$WORK/trunc/$1" "$2"
      echo "### $(rev_id $rev): $1 cut by $2 bytes"
      evidence "$(rev_bin $rev)" query "$WORK/trunc" "$Q" -f csv
    done
  done
} > "$OUT/truncation.txt" 2>&1

# Bit flips.
FILES="graph.nodedata graph.nodeoffsets forward.graph forward.offsets graph.labels graph.typeindex"
{
  echo "# Single-bit flips (fuzz.py; identities.txt)"
  echo "## $(rev_id base)"; python3 "$HERE/fuzz.py" "$BASE_BIN" "$WORK/base/fixture-graph" "$WORK/fz" $FILES
  echo "## $(rev_id cand)"; python3 "$HERE/fuzz.py" --expect-clean "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $FILES
  echo "## $(rev_id cand), GRAPHITE_VERIFY_GRAPHS=1"
  GRAPHITE_VERIFY_GRAPHS=1 python3 "$HERE/fuzz.py" --expect-clean "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $FILES
} > "$OUT/bitflip-fuzz.txt" 2>&1 || { echo "replay: a candidate bit flip panicked, crashed or exited unexpectedly; see bitflip-fuzz.txt" >&2; exit 1; }

# Engine drift: the candidate checkout's differential, run on each revision's two CLIs.
{
  echo "# Kotlin-vs-Rust CLI differential (backend/bench/parity-cli.py of $CAND_REV; identities.txt)"
  echo "## $(rev_id base)"
  python3 "$REPO/backend/bench/parity-cli.py" "$WORK/base/fixture-graph" "$BASE_JAR" "$BASE_BIN" 2>&1 || true
  echo "## $(rev_id cand)"
  python3 "$REPO/backend/bench/parity-cli.py" "$WORK/cand/fixture-graph" "$CAND_JAR" "$CAND_BIN" 2>&1
} > "$OUT/cli-differential.txt" || { echo "replay: the candidate CLI differential failed; see cli-differential.txt" >&2; exit 1; }

# Repeated builds in one JVM.
{
  echo "# Four builds of $(basename "$INPUT") in one JVM (repeated-builds.jsh; identities.txt)"
  for rev in base cand; do
    echo "## $(rev_id $rev)"
    mkdir -p "$WORK/mb-$rev"
    step "repeated-builds-$rev" jshell --class-path "$(rev_jar $rev)" -R-Dout="$WORK/mb-$rev" -R-Din="$INPUT" -R-Xmx3g "$HERE/repeated-builds.jsh"
    grep -q '^built 4$' "$LOGS/repeated-builds-$rev.log" || { echo "replay: repeated-builds-$rev did not build 4 graphs" >&2; exit 1; }
    echo "built 4"
    for g in "$WORK/mb-$rev"/g0*; do
      printf '%s: graph.nodeoffsets %s bytes; nodes,minId,maxId ' "$(basename "$g")" "$(stat -c %s "$g/graph.nodeoffsets")"
      "$CAND_BIN" query "$g" "MATCH (n) RETURN count(*) AS nodes, min(id(n)) AS minId, max(id(n)) AS maxId" -f csv | tail -1
    done
  done
} > "$OUT/repeated-builds.txt"

# JVM saves into an existing directory.
{
  echo "# GraphStore.save into an existing directory (save-into.jsh; identities.txt)"
  QN="MATCH (n) RETURN count(*) AS nodes"
  QE="MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t"
  QI="MATCH (a)<-[r]-(b) RETURN count(*) AS incoming"
  for rev in base cand; do
    JAR=$(rev_jar $rev) BIN=$(rev_bin $rev) W="$WORK/ds-$rev"
    rm -rf "$W"; mkdir -p "$W"
    cp -r "$WORK/$rev/acme-graph" "$W/acme"; cp -r "$WORK/$rev/fixture-graph" "$W/core"
    echo; echo "## $(rev_id $rev)"
    echo "### 1. save(loadMapped(acme), acme)"
    printf 'before: nodes %s; edges ' "$("$BIN" query "$W/acme" "$QN" -f csv | tail -1)"; "$BIN" query "$W/acme" "$QE" -f csv | tail -n +2 | tr '\n' ' '; echo
    step "save-same-$rev" jshell --class-path "$JAR" -R-Ddir="$W/acme" "$HERE/save-into.jsh"
    grep '^saved \|^save failed' "$LOGS/save-same-$rev.log" | sed "s|$WORK/||g"
    printf 'after:  nodes %s; edges ' "$(evidence "$BIN" query "$W/acme" "$QN" -f csv | tail -1)"; evidence "$BIN" query "$W/acme" "$QE" -f csv | tail -n +2 | tr '\n' ' '; echo
    printf 'after, JVM CLI: edges '; evidence java -jar "$JAR" query "$W/acme" "$QE" -f csv | quiet | tail -n +2 | tr '\n' ' '; echo
    echo "### 2. save(loadMapped(acme'), core) after the JVM CLI persisted core's transpose"
    rm -rf "$W/acme"; cp -r "$WORK/$rev/acme-graph" "$W/acme"
    java -jar "$JAR" query "$W/core" "$QI" -f csv 2>&1 | quiet >/dev/null
    echo "backward.* beside core before the save: $(count_backward "$W/core") files"
    step "save-into-$rev" jshell --class-path "$JAR" -R-Ddir="$W/core" -R-Dfrom="$W/acme" "$HERE/save-into.jsh"
    grep '^saved \|^save failed' "$LOGS/save-into-$rev.log" | sed "s|$WORK/||g"
    echo "backward.* left after the save, before any query: $(count_backward "$W/core") files"
    printf 'expected (Rust CLI, transpose built in memory): '; evidence "$BIN" query "$W/core" "$QI" -f csv | tail -1
    printf 'JVM CLI: '; evidence java -jar "$JAR" query "$W/core" "$QI" -f csv | quiet | tail -1
  done
} > "$OUT/jvm-storage-safety.txt" 2>&1 || { echo "replay: the storage-safety reproducer failed; see jvm-storage-safety.txt" >&2; exit 1; }

# Completeness facts.
{
  echo "# analysisStatus/dispatchGap on the acme graph (identities.txt)"
  echo "## $(rev_id base)"
  evidence "$BASE_BIN" query "$WORK/base/acme-graph" "MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status"
  echo "## $(rev_id cand)"
  python3 "$REPO/backend/bench/completeness-e2e.py" "$WORK/cand/acme-graph" "$CAND_JAR" "$CAND_BIN"
  "$CAND_BIN" query "$WORK/cand/acme-graph" "MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status"
} > "$OUT/completeness.txt" 2>&1 || { echo "replay: the candidate completeness check failed; see completeness.txt" >&2; exit 1; }

# Query service: each revision's own server on a port verified free, owned and ready.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'; }
start_server() { # rev -> sets SERVER and PORT, or ends the replay
  PORT=$(free_port)
  if curl -s -o /dev/null "localhost:$PORT/api/graphs"; then echo "replay: port $PORT is taken" >&2; exit 1; fi
  "$(rev_bin "$1")" serve --graph acme:"$WORK/$1/acme-graph" --port "$PORT" > "$LOGS/serve-$1.log" 2>&1 &
  SERVER=$!
  for _ in $(seq 1 240); do
    kill -0 "$SERVER" 2>/dev/null || { echo "replay: the $1 server exited before it was ready" >&2; tail -20 "$LOGS/serve-$1.log" >&2; exit 1; }
    curl -sf -o /dev/null "localhost:$PORT/api/graphs" && return 0
    sleep 0.5
  done
  echo "replay: the $1 server was not ready on port $PORT" >&2; exit 1
}
stop_server() { kill "$SERVER" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; SERVER=""; }
{
  echo "# HTTP on the acme graph, each revision's own server on a free port (identities.txt)"
  Q='MATCH%20(n:Method)%20RETURN%20n.name%20AS%20v%20ORDER%20BY%20v'
  for rev in base cand; do
    start_server $rev
    echo "## $(rev_id $rev) (server pid $SERVER)"
    for path in "/api/capabilities" "/api/graphs/acme/hierarchy?type=java.lang.Object&limit=3" \
                "/api/cypher?query=$Q&limit=5" "/api/cypher?query=$Q&limit=5&offset=5" \
                "/api/cypher?query=$Q&limit=5&offset=10000000"; do
      kill -0 "$SERVER" 2>/dev/null || { echo "replay: the $rev server died during the requests" >&2; exit 1; }
      code=$(curl -s -o "$WORK/body" -w '%{http_code}' "localhost:$PORT$path")
      echo "GET $path -> $code"; echo "    $(head -c 600 "$WORK/body")"
    done
    stop_server
  done
} > "$OUT/query-service.txt" 2>&1

echo "replayed into $OUT"
