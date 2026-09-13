#!/usr/bin/env bash
set -euo pipefail
test "$#" -eq 6 || { echo 'Usage: slow-shapes <controls> <base-gate> <base-source> <candidate-source> <android-fixture> <output>' >&2; exit 2; }
CONTROLS=$(realpath "$1")
GATE=$(realpath "$2")
BASE=$(realpath "$3")
CANDIDATE=$(realpath "$4")
FIXTURE=$(realpath "$5")
mkdir -p "$6"
OUTPUT=$(realpath "$6")
test ! -e "$OUTPUT/initial-base-slow-shapes.json" || { echo 'Existing measurement series; use a fresh output directory.' >&2; exit 1; }
HARNESS=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/SlowQueryShapesBenchmark.kt
CORPUS=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/BenchmarkCorpus.kt
EVALUATOR=graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/ExpressionEvaluator.kt
COMPARATOR="$CONTROLS/.github/scripts/benchmark-slow-query-shapes.mjs"
PATCH="$CONTROLS/.github/scripts/benchmark-slow-query-shapes-subscript.patch"
ALL_QUERIES=valueHit,valueMiss,qualifiedIdHit,qualifiedIdMiss,dynamicHit,dynamicMiss,wrappedCallerHit,wrappedCallerMiss,dataflowSourceHit,dataflowSourceMiss,dataflowTargetHit,dataflowTargetMiss
REGULAR_QUERIES=valueHit,valueMiss,qualifiedIdHit,qualifiedIdMiss,wrappedCallerHit,wrappedCallerMiss,dataflowSourceHit,dataflowSourceMiss,dataflowTargetHit,dataflowTargetMiss
DYNAMIC_QUERIES=dynamicHit,dynamicMiss
REFERENCE_KIND=unmodified-base
REFERENCE=
trap 'if [[ -n "$REFERENCE" ]]; then rm -rf -- "$REFERENCE"; fi' EXIT

# Repair only the exact reviewed legacy implementation. Unknown bases are never patched.
# They must pass the dynamic-hit oracle directly, or fail closed before timing.
if [[ $(sha256sum "$BASE/$EVALUATOR" | cut -d' ' -f1) == b4c1bb76cde0be2bcc7b3485e4ac92fd28e0555839cb7d0954748436868510c8 ]]; then
  test "$(sha256sum "$PATCH" | cut -d' ' -f1)" = 1ed0f211886dec1bfb2557983400afb34586405583acfbeef0f3f9c07136557a
  REFERENCE=$(mktemp -d "${RUNNER_TEMP:-/tmp}/graphite-slow-shapes-reference-XXXXXX")
  git clone --no-hardlinks "$BASE" "$REFERENCE"
  test "$(git -C "$REFERENCE" rev-parse HEAD)" = "$(git -C "$BASE" rev-parse HEAD)"
  git -C "$REFERENCE" apply --check "$PATCH"
  git -C "$REFERENCE" apply "$PATCH"
  test "$(sha256sum "$REFERENCE/$EVALUATOR" | cut -d' ' -f1)" = be13e38c5a14d37e363f902571c169d7c0ad52f30a12b11227de57f2c9eef5fa
  git -C "$REFERENCE" diff --binary > "$OUTPUT/slow-shapes-dynamic-reference.patch"
  cp "$REFERENCE/$EVALUATOR" "$OUTPUT/slow-shapes-dynamic-reference-ExpressionEvaluator.kt"
  REFERENCE_KIND=base-plus-subscript-correctness-repair
fi

INIT=.github/scripts/benchmark-jmh-isolation.init.gradle
VERIFIER=.github/scripts/verify-jmh-jar-isolation.sh
ISOLATION="$GATE"
if [[ ! -f "$GATE/$INIT" || ! -f "$GATE/$VERIFIER" ]]; then
  ISOLATION="$CANDIDATE"
  test "$(sha256sum "$ISOLATION/$INIT" | cut -d' ' -f1)" = 4d4f310a6f1c70894cdc4bc66c85ce189430a3af4f9e7661ac23a88b1fbd652f
  test "$(sha256sum "$ISOLATION/$VERIFIER" | cut -d' ' -f1)" = 15308f60de8e8e948870e04cd162a32a549f1ba40c5c320a9ec0e28da9cba29e
fi

build() {
  local revision=$1 source=$2
  test ! -L "$source/$HARNESS" && test ! -L "$source/$CORPUS"
  install -m 0644 "$CONTROLS/$HARNESS" "$source/$HARNESS"
  install -m 0644 "$GATE/$CORPUS" "$source/$CORPUS"
  cmp "$CONTROLS/$HARNESS" "$source/$HARNESS"
  cmp "$GATE/$CORPUS" "$source/$CORPUS"
  "$source/gradlew" -p "$source" -I "$ISOLATION/$INIT" \
    :webgraph:testClasses :webgraph:jmhJar --max-workers=2 --no-daemon \
    > "$OUTPUT/$revision-slow-shapes-build.log" 2>&1
  local jars=()
  mapfile -t jars < <(find "$source/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -type f)
  test "${#jars[@]}" -eq 1
  bash "$ISOLATION/$VERIFIER" "$source/graphite-webgraph" "${jars[0]}"
  install -m 0644 "${jars[0]}" "$OUTPUT/$revision-slow-shapes.jar"
  sha256sum "$OUTPUT/$revision-slow-shapes.jar" >> "$OUTPUT/slow-shapes-jars.sha256"
}
build base "$BASE"
build candidate "$CANDIDATE"
if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then build reference "$REFERENCE"; fi

node "$COMPARATOR" fingerprint --fixture "$FIXTURE" --output "$OUTPUT/slow-shapes-fixture-before.json"
ORACLE_JAR="$OUTPUT/base-slow-shapes.jar"
if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then ORACLE_JAR="$OUTPUT/reference-slow-shapes.jar"; fi
java -Xmx8g -XX:ActiveProcessorCount=4 "-Dandroid.graph.path=$FIXTURE" -cp "$ORACLE_JAR" \
  io.johnsonlee.graphite.webgraph.SlowQueryShapesCorrectness android dynamicHit \
  > "$OUTPUT/slow-shapes-dynamic-reference-preflight.log" 2>&1
jq -n --arg baseSha "$(git -C "$BASE" rev-parse HEAD)" \
  --arg candidateSha "$(git -C "$CANDIDATE" rev-parse HEAD)" \
  --arg referenceKind "$REFERENCE_KIND" --arg fixture "$FIXTURE" \
  --arg harnessSha256 "$(sha256sum "$CONTROLS/$HARNESS" | cut -d' ' -f1)" \
  --arg comparatorSha256 "$(sha256sum "$COMPARATOR" | cut -d' ' -f1)" \
  '{baseSha:$baseSha,candidateSha:$candidateSha,referenceKind:$referenceKind,fixture:$fixture,
    harnessSha256:$harnessSha256,comparatorSha256:$comparatorSha256,
    fixtureProtocol:"private-copy-no-callsite-index-v2",forks:3,thresholdPercent:15}' \
  > "$OUTPUT/slow-query-shapes-provenance.json"

measure() {
  local revision=$1 phase=$2 queries=$ALL_QUERIES
  if [[ "$revision" == base && "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then queries=$REGULAR_QUERIES; fi
  if [[ "$revision" == reference ]]; then queries=$DYNAMIC_QUERIES; fi
  java -jar "$OUTPUT/$revision-slow-shapes.jar" \
    'io.johnsonlee.graphite.webgraph.SlowQueryShapesBenchmark.execute' \
    -p corpus=android -p "queryName=$queries" -p cacheState=COLD,WARM \
    -f 3 -t 1 -wi 0 -i 1 -foe true -prof gc -rf json \
    -jvmArgsAppend "-Dandroid.graph.path=$FIXTURE" \
    -rff "$OUTPUT/$phase-$revision-slow-shapes.json" \
    > "$OUTPUT/$phase-$revision-slow-shapes.log" 2>&1
}
compare_phase() {
  local phase=$1
  local extra=()
  if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then
    extra=(--reference "$OUTPUT/$phase-reference-slow-shapes.json" --reference-log "$OUTPUT/$phase-reference-slow-shapes.log")
  fi
  node "$COMPARATOR" compare --fixture "$FIXTURE" --reference-kind "$REFERENCE_KIND" \
    --base "$OUTPUT/$phase-base-slow-shapes.json" --base-log "$OUTPUT/$phase-base-slow-shapes.log" \
    --candidate "$OUTPUT/$phase-candidate-slow-shapes.json" --candidate-log "$OUTPUT/$phase-candidate-slow-shapes.log" \
    "${extra[@]}" --status "$OUTPUT/$phase-slow-query-shapes-status.json" \
    --report "$OUTPUT/$phase-slow-query-shapes-report.md"
}
measure base initial
if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then measure reference initial; fi
measure candidate initial
if compare_phase initial; then
  cp "$OUTPUT/initial-slow-query-shapes-status.json" "$OUTPUT/slow-query-shapes-status.json"
  cp "$OUTPUT/initial-slow-query-shapes-report.md" "$OUTPUT/slow-query-shapes-report.md"
else
  # Integrity failures are permanent; only valid numerical suspects get another run.
  jq -e '.errors == [] and (.rows | length) == 24' "$OUTPUT/initial-slow-query-shapes-status.json" >/dev/null
  measure candidate confirmation
  if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair ]]; then measure reference confirmation; fi
  measure base confirmation
  compare_phase confirmation || true
  node "$COMPARATOR" confirm --initial "$OUTPUT/initial-slow-query-shapes-status.json" \
    --confirmation "$OUTPUT/confirmation-slow-query-shapes-status.json" \
    --status "$OUTPUT/slow-query-shapes-status.json" --report "$OUTPUT/slow-query-shapes-report.md"
fi
node "$COMPARATOR" verify-fixture --fixture "$FIXTURE" --before "$OUTPUT/slow-shapes-fixture-before.json" \
  --output "$OUTPUT/slow-shapes-fixture-verification.json" \
  --status "$OUTPUT/slow-query-shapes-status.json" --report "$OUTPUT/slow-query-shapes-report.md"
