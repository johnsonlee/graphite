#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo 'Usage: build-wide-latency-bundle.sh <candidate-tree> <shared-fixture64> <base-sha> <candidate-sha> <output>' >&2
  exit 2
fi
CANDIDATE_TREE=$(cd "$1" && pwd)
SHARED=$(cd "$2" && pwd)
BASE_SHA=$3
CANDIDATE_SHA=$4
test ! -e "$5"
mkdir -p "$5"
OUTPUT=$(cd "$5" && pwd)
[[ "$BASE_SHA" =~ ^[a-f0-9]{40}$ && "$CANDIDATE_SHA" =~ ^[a-f0-9]{40}$ ]]
test "$(git -C "$CANDIDATE_TREE" rev-parse HEAD)" = "$CANDIDATE_SHA"
test "$(git -C "$CANDIDATE_TREE" rev-parse "$BASE_SHA^{commit}")" = "$BASE_SHA"
BUILD_ROOT=$(mktemp -d)
trap 'rm -rf "$BUILD_ROOT"' EXIT
BASE_TREE=$BUILD_ROOT/base
git clone --no-checkout "$CANDIDATE_TREE" "$BASE_TREE"
git -C "$BASE_TREE" checkout --detach "$BASE_SHA"
HARNESS=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt
CORRECTNESS=graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt
# Production commits remain intact; both JARs receive the same reviewed measurement code.
for SOURCE in "$HARNESS" "$CORRECTNESS"; do
  cp "$CANDIDATE_TREE/$SOURCE" "$BASE_TREE/$SOURCE"
  cmp "$CANDIDATE_TREE/$SOURCE" "$BASE_TREE/$SOURCE"
done
"$BASE_TREE/gradlew" -p "$BASE_TREE" :webgraph:jmhJar --no-daemon
"$CANDIDATE_TREE/gradlew" -p "$CANDIDATE_TREE" :webgraph:jmhJar :webgraph:prepareBenchmarkFixtures --no-daemon
BASE_JAR=$(find "$BASE_TREE/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
CANDIDATE_JAR=$(find "$CANDIDATE_TREE/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
FIXTURES=$CANDIDATE_TREE/graphite-webgraph/build/benchmark-fixtures
"$CANDIDATE_TREE/.github/scripts/verify-shared-fixture64.sh" "$CANDIDATE_JAR" "$FIXTURES" "$SHARED" "$CANDIDATE_SHA"
cp "$BASE_JAR" "$OUTPUT/base.jar"
cp "$CANDIDATE_JAR" "$OUTPUT/candidate.jar"
cp "$CANDIDATE_TREE/$HARNESS" "$OUTPUT/harness.kt"
cp "$CANDIDATE_TREE/$CORRECTNESS" "$OUTPUT/correctness.kt"
cp "$CANDIDATE_TREE/.github/scripts/wide-query-catalog.json" "$OUTPUT/catalog.json"
cp "$SHARED/graphs/graphs.tsv" "$SHARED/graphs/fixture-provenance.tsv" "$OUTPUT/"
cp "$SHARED/fixture-reproducibility.json" "$SHARED/fixture64.complete.json" "$OUTPUT/"
cp -R "$FIXTURES" "$OUTPUT/fixtures"
# Record once from the exact base. Record mode always covers all 72 queries.
java -jar "$OUTPUT/base.jar" LargeBroadQueryPressureBenchmark.replayBroadQueries \
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000 \
  -wi 0 -i 1 -f 1 -to 30m -foe true -rf json -rff "$OUTPUT/oracle-run.json" \
  -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=$SHARED/graphs/graphs.tsv \
    -Dgraphite.broad.pressure.correctness.mode=record \
    -Dgraphite.broad.pressure.latency.only=true \
    -Dgraphite.broad.pressure.latency.oracle=$OUTPUT/oracle.correctness \
    -Dgraphite.broad.pressure.latency.output=$OUTPUT/oracle-record.tsv"
node "$CANDIDATE_TREE/.github/scripts/benchmark-wide-shards.mjs" seal-build \
  --directory "$OUTPUT" --base "$BASE_SHA" --candidate "$CANDIDATE_SHA"
