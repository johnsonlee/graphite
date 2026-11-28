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
#
# Every required command is checked on its own (`required`), never by a handler on the
# block around it: `{ ...; } || handler` turns errexit off for the whole block, so a failed
# command followed by a passing one would leave the block, and the replay, successful.
# Every JVM the replay starts is bounded: JAVA_TOOL_OPTIONS sets -Xmx$REPLAY_HEAP (default 4g,
# refused above 8g) and asks each JVM to log the heap it actually got; heap.py reads those logs
# into jvm-heap.txt and fails the replay when a JVM ran above 8 GiB or did not log. The CLI's own
# probes (frontend version, fold plan) replace JAVA_TOOL_OPTIONS with -Xmx256m and are not logged.
#
# Sourced with REPLAY_SOURCE_ONLY=1, the script defines its sections without running them,
# which is how test_replay.py drives each one with failing stubs.
set -euo pipefail
exec 3>&2   # The replay's own messages; a section's stderr goes to its evidence file.

fail() { echo "replay: $*" >&3; exit 1; }
# Runs a command the replay needs; its output stays where the caller sends it.
required() { local what=$1; shift; "$@" || fail "$what failed (exit $?)"; }
# Runs a setup step, its output kept in $LOGS/<name>.log; a failure ends the replay.
step() {
  local name=$1; shift
  if ! "$@" > "$LOGS/$name.log" 2>&1; then
    echo "replay: step '$name' failed: $*" >&3
    tail -20 "$LOGS/$name.log" >&3
    exit 1
  fi
}
# Runs a command whose outcome is evidence: its output, and its exit code when it fails,
# are recorded and the replay goes on.
evidence() { local code=0; "$@" 2>&1 || code=$?; [ $code -eq 0 ] || echo "exit=$code"; }
rev_bin() { [ "$1" = base ] && echo "$BASE_BIN" || echo "$CAND_BIN"; }
rev_jar() { [ "$1" = base ] && echo "$BASE_JAR" || echo "$CAND_JAR"; }
rev_id() { [ "$1" = base ] && echo "baseline $BASE_REV" || echo "candidate $CAND_REV"; }
quiet() { grep -v '^Picked up ' || true; }
# Bounds every JVM's heap and has each log the heap it got, in $HEAP_LOGS.
bound_jvm_heap() {
  local heap=${REPLAY_HEAP:-4g} n unit
  [[ $heap =~ ^([0-9]+)([mMgG])$ ]] || fail "REPLAY_HEAP=$heap is not <n>m or <n>g"
  n=${BASH_REMATCH[1]} unit=${BASH_REMATCH[2]}
  [[ $unit == [gG] ]] && n=$((n * 1024))
  [ "$n" -le 8192 ] || fail "REPLAY_HEAP=$heap exceeds the 8 GiB heap ceiling"
  [[ $HEAP_LOGS != *[[:space:]]* ]] || fail "the heap log directory $HEAP_LOGS holds whitespace"
  rm -rf "$HEAP_LOGS"; mkdir -p "$HEAP_LOGS"
  export JAVA_TOOL_OPTIONS="-Xmx$heap -Xlog:gc+init=info:file=$HEAP_LOGS/jvm-%p-%t.log"
}
heap_section() {
  python3 "$HERE/heap.py" "$HEAP_LOGS" > "$OUT/jvm-heap.txt" \
    || fail "a JVM ran above the 8 GiB heap ceiling or logged no heap (see jvm-heap.txt)"
}
count_backward() { ls "$1" | grep -c '^backward\.' || true; }

identities() {
  mkdir -p "$WORK/acme-classes"
  step javac-acme javac --release 17 -d "$WORK/acme-classes" $(find "$REPO/backend/bench/fixtures/acme/src" -name '*.java')
  step jar-acme jar cfe "$WORK/acme.jar" com.acme.shop.api.Main -C "$WORK/acme-classes" com
  {
    echo "# Identities of this replay"
    echo "baseline revision:  $BASE_REV"
    echo "candidate revision: $CAND_REV"
    sha256sum "$BASE_BIN" "$BASE_JAR" "$CAND_BIN" "$CAND_JAR" "$INPUT" "$WORK/acme.jar" | sed "s|$WORK/||"
    echo "acme sources: $(cd "$REPO" && git rev-parse HEAD 2>/dev/null) backend/bench/fixtures/acme/src"
    echo "JAVA_TOOL_OPTIONS (every JVM): $JAVA_TOOL_OPTIONS" | sed "s|$OUT/||"
    echo "_JAVA_OPTIONS (inherited): ${_JAVA_OPTIONS:-}"
    { java -version 2>&1 || true; } | quiet | head -1
    javac -version 2>&1 | quiet
    uname -srm
  } > "$OUT/identities.txt"
}

# Graphs, built by each revision's own frontend.
build() { # rev input include name
  local dir="$WORK/$1/$4"
  mkdir -p "$WORK/$1"
  step "build-$1-$4" env GRAPHITE_FRONTEND_JVM="$(rev_jar "$1")" "$(rev_bin "$1")" build "$2" -o "$dir" --include "$3"
  step "unpack-$1-$4" "$(rev_bin "$1")" unpack "$dir.graphite" "$dir"
  rm -rf "$dir/META-INF"
}
graphs() {
  for rev in base cand; do
    build $rev "$INPUT" io.johnsonlee.graphite fixture-graph
    build $rev "$WORK/acme.jar" com.acme acme-graph
  done
}

# Truncation: a file cut short, the edge histogram it answers.
truncation_section() {
  local Q="MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t"
  {
    echo "# Truncated graph files: what a query answers (identities.txt)"
    for rev in base cand; do
      echo "### $(rev_id $rev): undamaged"
      required "the $rev query on its undamaged graph" "$(rev_bin $rev)" query "$WORK/$rev/fixture-graph" "$Q" -f csv
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
}

# Bit flips: the baseline's outcomes are evidence; the candidate's must be clean, in both runs.
bitflip_section() {
  local files="graph.nodedata graph.nodeoffsets forward.graph forward.offsets graph.labels graph.typeindex"
  {
    echo "# Single-bit flips (fuzz.py; identities.txt)"
    echo "## $(rev_id base)"
    required "the baseline bit-flip run" python3 "$HERE/fuzz.py" "$BASE_BIN" "$WORK/base/fixture-graph" "$WORK/fz" $files
    echo "## $(rev_id cand)"
    required "the candidate bit-flip run (a panic, crash or unexpected exit; see bitflip-fuzz.txt)" \
      python3 "$HERE/fuzz.py" --expect-clean "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $files
    echo "## $(rev_id cand), GRAPHITE_VERIFY_GRAPHS=1"
    required "the candidate bit-flip run under GRAPHITE_VERIFY_GRAPHS=1 (see bitflip-fuzz.txt)" \
      env GRAPHITE_VERIFY_GRAPHS=1 python3 "$HERE/fuzz.py" --expect-clean "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $files
  } > "$OUT/bitflip-fuzz.txt" 2>&1
}

# Engine drift: the candidate checkout's differential, run on each revision's two CLIs.
differential_section() {
  {
    echo "# Kotlin-vs-Rust CLI differential (backend/bench/parity-cli.py of $CAND_REV; identities.txt)"
    echo "## $(rev_id base)"
    evidence python3 "$REPO/backend/bench/parity-cli.py" "$WORK/base/fixture-graph" "$BASE_JAR" "$BASE_BIN"
    echo "## $(rev_id cand)"
    required "the candidate CLI differential (see cli-differential.txt)" \
      python3 "$REPO/backend/bench/parity-cli.py" "$WORK/cand/fixture-graph" "$CAND_JAR" "$CAND_BIN"
  } > "$OUT/cli-differential.txt" 2>&1
}

# Repeated builds in one JVM.
repeated_builds_section() {
  {
    echo "# Four builds of $(basename "$INPUT") in one JVM (repeated-builds.jsh; identities.txt)"
    for rev in base cand; do
      echo "## $(rev_id $rev)"
      mkdir -p "$WORK/mb-$rev"
      step "repeated-builds-$rev" jshell --class-path "$(rev_jar $rev)" -R-Dout="$WORK/mb-$rev" -R-Din="$INPUT" -R-Xmx3g "$HERE/repeated-builds.jsh"
      grep -q '^built 4$' "$LOGS/repeated-builds-$rev.log" || fail "repeated-builds-$rev did not build 4 graphs"
      echo "built 4"
      for g in "$WORK/mb-$rev"/g0*; do
        printf '%s: graph.nodeoffsets %s bytes; nodes,minId,maxId ' "$(basename "$g")" "$(stat -c %s "$g/graph.nodeoffsets")"
        required "the node-id query on $(basename "$g")" "$CAND_BIN" query "$g" "MATCH (n) RETURN count(*) AS nodes, min(id(n)) AS minId, max(id(n)) AS maxId" -f csv | tail -1
      done
    done
  } > "$OUT/repeated-builds.txt"
}

# JVM saves into an existing directory.
storage_section() {
  local QN="MATCH (n) RETURN count(*) AS nodes"
  local QE="MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t"
  local QI="MATCH (a)<-[r]-(b) RETURN count(*) AS incoming"
  {
    echo "# GraphStore.save into an existing directory (save-into.jsh; identities.txt)"
    for rev in base cand; do
      local JAR BIN W
      JAR=$(rev_jar $rev) BIN=$(rev_bin $rev) W="$WORK/ds-$rev"
      rm -rf "$W"; mkdir -p "$W"
      cp -r "$WORK/$rev/acme-graph" "$W/acme"; cp -r "$WORK/$rev/fixture-graph" "$W/core"
      echo; echo "## $(rev_id $rev)"
      echo "### 1. save(loadMapped(acme), acme)"
      local nodes
      nodes=$(required "the $rev node count before the save" "$BIN" query "$W/acme" "$QN" -f csv | tail -1)
      printf 'before: nodes %s; edges ' "$nodes"
      required "the $rev edge histogram before the save" "$BIN" query "$W/acme" "$QE" -f csv | tail -n +2 | tr '\n' ' '; echo
      step "save-same-$rev" jshell --class-path "$JAR" -R-Ddir="$W/acme" "$HERE/save-into.jsh"
      grep '^saved \|^save failed' "$LOGS/save-same-$rev.log" | sed "s|$WORK/||g"
      printf 'after:  nodes %s; edges ' "$(evidence "$BIN" query "$W/acme" "$QN" -f csv | tail -1)"; evidence "$BIN" query "$W/acme" "$QE" -f csv | tail -n +2 | tr '\n' ' '; echo
      printf 'after, JVM CLI: edges '; evidence java -jar "$JAR" query "$W/acme" "$QE" -f csv | quiet | tail -n +2 | tr '\n' ' '; echo
      echo "### 2. save(loadMapped(acme'), core) after the JVM CLI persisted core's transpose"
      rm -rf "$W/acme"; cp -r "$WORK/$rev/acme-graph" "$W/acme"
      required "the $rev JVM query that persists the transpose" java -jar "$JAR" query "$W/core" "$QI" -f csv > /dev/null 2>&1
      echo "backward.* beside core before the save: $(count_backward "$W/core") files"
      step "save-into-$rev" jshell --class-path "$JAR" -R-Ddir="$W/core" -R-Dfrom="$W/acme" "$HERE/save-into.jsh"
      grep '^saved \|^save failed' "$LOGS/save-into-$rev.log" | sed "s|$WORK/||g"
      echo "backward.* left after the save, before any query: $(count_backward "$W/core") files"
      printf 'expected (Rust CLI, transpose built in memory): '; evidence "$BIN" query "$W/core" "$QI" -f csv | tail -1
      printf 'JVM CLI: '; evidence java -jar "$JAR" query "$W/core" "$QI" -f csv | quiet | tail -1
    done
  } > "$OUT/jvm-storage-safety.txt" 2>&1
}

# Completeness facts: the baseline's refusal is evidence; the candidate's checks must pass.
completeness_section() {
  local Q="MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status"
  {
    echo "# analysisStatus/dispatchGap on the acme graph (identities.txt)"
    echo "## $(rev_id base)"
    evidence "$BASE_BIN" query "$WORK/base/acme-graph" "$Q"
    echo "## $(rev_id cand)"
    required "the candidate completeness check (see completeness.txt)" \
      python3 "$REPO/backend/bench/completeness-e2e.py" "$WORK/cand/acme-graph" "$CAND_JAR" "$CAND_BIN"
    required "the candidate analysisStatus query" "$CAND_BIN" query "$WORK/cand/acme-graph" "$Q"
  } > "$OUT/completeness.txt" 2>&1
}

# Query service: each revision's own server on a port verified free, owned and ready.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'; }
start_server() { # rev -> sets SERVER and PORT, or ends the replay
  PORT=$(free_port)
  if curl -s -o /dev/null "localhost:$PORT/api/graphs"; then fail "port $PORT is taken"; fi
  "$(rev_bin "$1")" serve --graph acme:"$WORK/$1/acme-graph" --port "$PORT" > "$LOGS/serve-$1.log" 2>&1 &
  SERVER=$!
  for _ in $(seq 1 240); do
    if ! kill -0 "$SERVER" 2>/dev/null; then
      tail -20 "$LOGS/serve-$1.log" >&3
      fail "the $1 server exited before it was ready"
    fi
    curl -sf -o /dev/null "localhost:$PORT/api/graphs" && return 0
    sleep 0.5
  done
  fail "the $1 server was not ready on port $PORT"
}
stop_server() { kill "$SERVER" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; SERVER=""; }
service_section() {
  local Q='MATCH%20(n:Method)%20RETURN%20n.name%20AS%20v%20ORDER%20BY%20v'
  {
    echo "# HTTP on the acme graph, each revision's own server on a free port (identities.txt)"
    for rev in base cand; do
      start_server $rev
      echo "## $(rev_id $rev) (server pid $SERVER)"
      for path in "/api/capabilities" "/api/graphs/acme/hierarchy?type=java.lang.Object&limit=3" \
                  "/api/cypher?query=$Q&limit=5" "/api/cypher?query=$Q&limit=5&offset=5" \
                  "/api/cypher?query=$Q&limit=5&offset=10000000"; do
        kill -0 "$SERVER" 2>/dev/null || fail "the $rev server died during the requests"
        code=$(curl -s -o "$WORK/body" -w '%{http_code}' "localhost:$PORT$path") || fail "GET $path on the $rev server"
        echo "GET $path -> $code"; echo "    $(head -c 600 "$WORK/body")"
      done
      stop_server
    done
  } > "$OUT/query-service.txt" 2>&1
}

finish() {
  local status=$?
  [ -n "${SERVER:-}" ] && kill "$SERVER" 2>/dev/null || true
  rm -rf "$WORK"
  if [ $status -eq 0 ]; then rm -rf "$LOGS"; else echo "replay failed (exit $status); step logs in $LOGS" >&3; fi
}

main() {
  [ $# -eq 8 ] || { sed -n '2,20p' "$0" >&3; exit 2; }
  BASE_REV=$1 BASE_BIN=$(realpath "$2") BASE_JAR=$(realpath "$3")
  CAND_REV=$4 CAND_BIN=$(realpath "$5") CAND_JAR=$(realpath "$6")
  INPUT=$(realpath "$7") OUT=$(realpath -m "$8")
  HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  REPO=$(cd "$HERE/../../.." && pwd)
  WORK=$(mktemp -d)
  LOGS="$OUT/replay-logs"
  SERVER=""
  mkdir -p "$OUT"
  rm -rf "$LOGS"; mkdir -p "$LOGS"
  HEAP_LOGS="$LOGS/jvm-heap"
  trap finish EXIT
  bound_jvm_heap
  identities
  graphs
  truncation_section
  bitflip_section
  differential_section
  repeated_builds_section
  storage_section
  completeness_section
  service_section
  heap_section
  echo "replayed into $OUT"
}

if [ "${REPLAY_SOURCE_ONLY:-}" != 1 ]; then
  main "$@"
fi
