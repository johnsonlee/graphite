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
set -u
[ $# -eq 8 ] || { sed -n '2,13p' "$0"; exit 2; }
BASE_REV=$1 BASE_BIN=$(realpath "$2") BASE_JAR=$(realpath "$3")
CAND_REV=$4 CAND_BIN=$(realpath "$5") CAND_JAR=$(realpath "$6")
INPUT=$(realpath "$7") OUT=$(realpath -m "$8")
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

rev_bin() { [ "$1" = base ] && echo "$BASE_BIN" || echo "$CAND_BIN"; }
rev_jar() { [ "$1" = base ] && echo "$BASE_JAR" || echo "$CAND_JAR"; }
rev_id() { [ "$1" = base ] && echo "baseline $BASE_REV" || echo "candidate $CAND_REV"; }
quiet() { grep -v '^Picked up '; }

# Identities.
mkdir -p "$WORK/acme-classes"
javac --release 17 -d "$WORK/acme-classes" $(find "$REPO/backend/bench/fixtures/acme/src" -name '*.java') 2>&1 | quiet
(cd "$WORK/acme-classes" && jar cfe "$WORK/acme.jar" com.acme.shop.api.Main com)
{
  echo "# Identities of this replay"
  echo "baseline revision:  $BASE_REV"
  echo "candidate revision: $CAND_REV"
  sha256sum "$BASE_BIN" "$BASE_JAR" "$CAND_BIN" "$CAND_JAR" "$INPUT" "$WORK/acme.jar" | sed "s|$WORK/||"
  echo "acme sources: $(cd "$REPO" && git rev-parse HEAD 2>/dev/null) backend/bench/fixtures/acme/src"
  java -version 2>&1 | quiet | head -1
  javac -version 2>&1 | quiet
  uname -srm
} > "$OUT/identities.txt"

# Graphs, built by each revision's own frontend.
build() { # rev input include name
  local dir="$WORK/$1/$4"
  GRAPHITE_FRONTEND_JVM="$(rev_jar "$1")" "$(rev_bin "$1")" build "$2" -o "$dir" --include "$3" >/dev/null 2>&1
  "$(rev_bin "$1")" unpack "$dir.graphite" "$dir" >/dev/null 2>&1
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
      "$(rev_bin $rev)" query "$WORK/trunc" "$Q" -f csv 2>&1; echo "exit=$?"
    done
  done
} > "$OUT/truncation.txt" 2>&1

# Bit flips.
FILES="graph.nodedata graph.nodeoffsets forward.graph forward.offsets graph.labels graph.typeindex"
{
  echo "# Single-bit flips (fuzz.py; identities.txt)"
  echo "## $(rev_id base)"; python3 "$HERE/fuzz.py" "$BASE_BIN" "$WORK/base/fixture-graph" "$WORK/fz" $FILES
  echo "## $(rev_id cand)"; python3 "$HERE/fuzz.py" "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $FILES
  echo "## $(rev_id cand), GRAPHITE_VERIFY_GRAPHS=1"
  GRAPHITE_VERIFY_GRAPHS=1 python3 "$HERE/fuzz.py" "$CAND_BIN" "$WORK/cand/fixture-graph" "$WORK/fz" $FILES
} > "$OUT/bitflip-fuzz.txt" 2>&1

# Engine drift: the candidate checkout's differential, run on each revision's two CLIs.
{
  echo "# Kotlin-vs-Rust CLI differential (backend/bench/parity-cli.py of $CAND_REV; identities.txt)"
  for rev in base cand; do
    echo "## $(rev_id $rev)"
    python3 "$REPO/backend/bench/parity-cli.py" "$WORK/$rev/fixture-graph" "$(rev_jar $rev)" "$(rev_bin $rev)" 2>&1
  done
} > "$OUT/cli-differential.txt"

# Repeated builds in one JVM.
{
  echo "# Four builds of $(basename "$INPUT") in one JVM (repeated-builds.jsh; identities.txt)"
  for rev in base cand; do
    echo "## $(rev_id $rev)"
    mkdir -p "$WORK/mb-$rev"
    jshell --class-path "$(rev_jar $rev)" -R-Dout="$WORK/mb-$rev" -R-Din="$INPUT" -R-Xmx3g "$HERE/repeated-builds.jsh" 2>&1 | quiet | tail -1
    for g in "$WORK/mb-$rev"/g0*; do
      printf '%s: graph.nodeoffsets %s bytes; nodes,minId,maxId ' "$(basename "$g")" "$(stat -c %s "$g/graph.nodeoffsets")"
      "$CAND_BIN" query "$g" "MATCH (n) RETURN count(*) AS nodes, min(id(n)) AS minId, max(id(n)) AS maxId" -f csv | tail -1
    done
  done
} > "$OUT/repeated-builds.txt" 2>&1

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
    jshell --class-path "$JAR" -R-Ddir="$W/acme" "$HERE/save-into.jsh" 2>&1 | quiet | tail -1
    printf 'after:  nodes %s; edges ' "$("$BIN" query "$W/acme" "$QN" -f csv 2>&1 | tail -1)"; "$BIN" query "$W/acme" "$QE" -f csv 2>&1 | tail -n +2 | tr '\n' ' '; echo
    printf 'after, JVM CLI: edges '; java -jar "$JAR" query "$W/acme" "$QE" -f csv 2>&1 | quiet | tail -n +2 | tr '\n' ' '; echo
    echo "### 2. save(loadMapped(acme'), core) after the JVM CLI persisted core's transpose"
    rm -rf "$W/acme"; cp -r "$WORK/$rev/acme-graph" "$W/acme"
    java -jar "$JAR" query "$W/core" "$QI" -f csv 2>&1 | quiet >/dev/null
    echo "backward.* beside core before the save: $(ls "$W/core" | grep -c '^backward\.') files"
    jshell --class-path "$JAR" -R-Ddir="$W/core" -R-Dfrom="$W/acme" "$HERE/save-into.jsh" 2>&1 | quiet | tail -1
    echo "backward.* left after the save, before any query: $(ls "$W/core" | grep -c '^backward\.') files"
    printf 'expected (Rust CLI, transpose built in memory): '; "$BIN" query "$W/core" "$QI" -f csv 2>&1 | tail -1
    printf 'JVM CLI: '; java -jar "$JAR" query "$W/core" "$QI" -f csv 2>&1 | quiet | tail -1
  done
} > "$OUT/jvm-storage-safety.txt" 2>&1

# Completeness facts.
{
  echo "# analysisStatus/dispatchGap on the acme graph (identities.txt)"
  echo "## $(rev_id base)"
  "$BASE_BIN" query "$WORK/base/acme-graph" "MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status" 2>&1; echo "exit=$?"
  echo "## $(rev_id cand)"
  python3 "$REPO/backend/bench/completeness-e2e.py" "$WORK/cand/acme-graph" "$CAND_JAR" "$CAND_BIN"
  "$CAND_BIN" query "$WORK/cand/acme-graph" "MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status"
} > "$OUT/completeness.txt" 2>&1

# Query service.
{
  echo "# HTTP on the acme graph, each revision's own server on port 18090 (identities.txt)"
  Q='MATCH%20(n:Method)%20RETURN%20n.name%20AS%20v%20ORDER%20BY%20v'
  for rev in base cand; do
    "$(rev_bin $rev)" serve --graph acme:"$WORK/$rev/acme-graph" --port 18090 > "$WORK/serve-$rev.log" 2>&1 &
    P=$!
    for _ in $(seq 1 120); do curl -sf localhost:18090/api/graphs >/dev/null && break; sleep 0.5; done
    echo "## $(rev_id $rev)"
    for path in "/api/capabilities" "/api/graphs/acme/hierarchy?type=java.lang.Object&limit=3" \
                "/api/cypher?query=$Q&limit=5" "/api/cypher?query=$Q&limit=5&offset=5" \
                "/api/cypher?query=$Q&limit=5&offset=10000000"; do
      code=$(curl -s -o "$WORK/body" -w '%{http_code}' "localhost:18090$path")
      echo "GET $path -> $code"; echo "    $(head -c 600 "$WORK/body")"
    done
    kill $P; wait $P 2>/dev/null
  done
} > "$OUT/query-service.txt" 2>&1

echo "replayed into $OUT"
