#!/usr/bin/env bash
set -euo pipefail
[[ "$#" == 6 ]] || { echo 'Usage: slow-query-warm <controls> <base-source> <candidate-source> <fixture> <output> <reference-kind>' >&2; exit 2; }
CONTROLS=$(realpath "$1")
BASE=$(realpath "$2")
CANDIDATE=$(realpath "$3")
FIXTURE=$(realpath "$4")
OUTPUT=$(realpath "$5")
REFERENCE_KIND=$6
COMPARATOR="$CONTROLS/.github/scripts/benchmark-slow-query-warm.mjs"
FIXTURE_TOOL="$CONTROLS/.github/scripts/benchmark-slow-query-shapes.mjs"
HARNESS=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/SlowQueryShapesBenchmark.kt
ENTRY=io.johnsonlee.graphite.webgraph.SlowQueryShapesSteadyState
QUERIES=(valueHit valueMiss qualifiedIdHit qualifiedIdMiss dynamicHit dynamicMiss wrappedCallerHit wrappedCallerMiss dataflowSourceHit dataflowSourceMiss dataflowTargetHit dataflowTargetMiss)
test ! -e "$OUTPUT/slow-warm-oracle.tsv"
test ! -e "$OUTPUT/slow-warm-runs.jsonl"
touch "$OUTPUT/slow-warm-runs.jsonl"
printf '%s\n' '{"passed":false,"errors":["Steady-state measurements incomplete"],"rows":[]}' > "$OUTPUT/slow-query-shapes-status.json"
node "$FIXTURE_TOOL" fingerprint --fixture "$FIXTURE" --output "$OUTPUT/slow-shapes-fixture-before.json"
jq -n --arg baseSha "$(git -C "$BASE" rev-parse HEAD)" \
  --arg candidateSha "$(git -C "$CANDIDATE" rev-parse HEAD)" \
  --arg referenceKind "$REFERENCE_KIND" --arg fixture "$FIXTURE" \
  --arg hostname "${RUNNER_NAME:-$(hostname)}" --arg runId "${GITHUB_RUN_ID:-$(date +%s)}" \
  --arg runAttempt "${GITHUB_RUN_ATTEMPT:-1}" \
  --arg harnessSha256 "$(sha256sum "$CONTROLS/$HARNESS" | cut -d' ' -f1)" \
  --arg comparatorSha256 "$(sha256sum "$COMPARATOR" | cut -d' ' -f1)" \
  '{baseSha:$baseSha,candidateSha:$candidateSha,referenceKind:$referenceKind,fixture:$fixture,
    schema:"graphite-slow-warm-provenance-v1",hostname:$hostname,runId:$runId,runAttempt:$runAttempt,
    harnessSha256:$harnessSha256,comparatorSha256:$comparatorSha256,activeProcessorCount:4,
    fixtureProtocol:"private-copy-no-callsite-index-v2",forks:3,thresholdPercent:5,
    protocol:"slow-query-warm-v1",order:["candidate","base","base","candidate","candidate","base"],
    queries:["valueHit","valueMiss","qualifiedIdHit","qualifiedIdMiss","dynamicHit","dynamicMiss",
      "wrappedCallerHit","wrappedCallerMiss","dataflowSourceHit","dataflowSourceMiss","dataflowTargetHit","dataflowTargetMiss"]}' \
  > "$OUTPUT/slow-query-shapes-provenance.json"

file_hash() { if [[ -f "$1" ]]; then sha256sum "$1" | cut -d' ' -f1; fi; }
seal_provenance() {
  local runtime=""
  if [[ -f "$OUTPUT/valueHit-oracle.log" ]]; then
    runtime=$(awk -F '\t' '/^SLOW_QUERY_WARM_RUNTIME/ { sub(/^vmVersion=/,"",$2); print $2 }' "$OUTPUT/valueHit-oracle.log")
  fi
  jq --arg runtimeVersion "$runtime" \
    --arg oracleSha256 "$(file_hash "$OUTPUT/slow-warm-oracle.tsv")" \
    --arg fixtureBeforeSha256 "$(file_hash "$OUTPUT/slow-shapes-fixture-before.json")" \
    --arg fixtureAfterSha256 "$(file_hash "$OUTPUT/slow-shapes-fixture-after.json")" \
    --arg baseJarSha256 "$(file_hash "$OUTPUT/base-slow-shapes.jar")" \
    --arg candidateJarSha256 "$(file_hash "$OUTPUT/candidate-slow-shapes.jar")" \
    --arg referenceJarSha256 "$(file_hash "$OUTPUT/reference-slow-shapes.jar")" \
    --slurpfile runs "$OUTPUT/slow-warm-runs.jsonl" \
    '. + {runtimeVersion:$runtimeVersion,oracleSha256:$oracleSha256,
      fixtureBeforeSha256:$fixtureBeforeSha256,fixtureAfterSha256:$fixtureAfterSha256,
      baseJarSha256:$baseJarSha256,candidateJarSha256:$candidateJarSha256,
      referenceJarSha256:$referenceJarSha256,runs:$runs}' \
    "$OUTPUT/slow-query-shapes-provenance.json" > "$OUTPUT/slow-warm-sealed.json"
  mv "$OUTPUT/slow-warm-sealed.json" "$OUTPUT/slow-query-shapes-provenance.json"
}

# Always inspect shared fixture bytes, including after a query or comparison failure.
# The comparator owns the final verdict and rejects incomplete evidence.
finish() {
  local execution_status=$?
  trap - EXIT
  set +e
  node "$FIXTURE_TOOL" fingerprint --fixture "$FIXTURE" --output "$OUTPUT/slow-shapes-fixture-after.json"
  local fixture_status=$?
  seal_provenance
  local seal_status=$?
  node "$COMPARATOR" compare --directory "$OUTPUT" --jars-directory "$OUTPUT" --oracle "$OUTPUT/slow-warm-oracle.tsv" \
    --provenance "$OUTPUT/slow-query-shapes-provenance.json" \
    --fixture-before "$OUTPUT/slow-shapes-fixture-before.json" \
    --fixture-after "$OUTPUT/slow-shapes-fixture-after.json" \
    --status "$OUTPUT/slow-query-shapes-status.json" --report "$OUTPUT/slow-query-shapes-report.md"
  local comparison_status=$?
  if (( execution_status != 0 || fixture_status != 0 || seal_status != 0 || comparison_status != 0 )); then
    # An external execution error cannot be cleared by an otherwise complete comparison.
    if (( execution_status != 0 || fixture_status != 0 || seal_status != 0 )); then
      jq '.passed=false | .errors += ["Execution or final fixture verification failed"]' \
        "$OUTPUT/slow-query-shapes-status.json" > "$OUTPUT/failed-status.json"
      mv "$OUTPUT/failed-status.json" "$OUTPUT/slow-query-shapes-status.json"
      printf '\nExecution or final fixture verification failed.\n' >> "$OUTPUT/slow-query-shapes-report.md"
    fi
    exit 1
  fi
}
trap finish EXIT

baseline_jar() {
  if [[ "$REFERENCE_KIND" == base-plus-subscript-correctness-repair && "$1" == dynamic* ]]; then
    printf '%s\n' "$OUTPUT/reference-slow-shapes.jar"
  else
    printf '%s\n' "$OUTPUT/base-slow-shapes.jar"
  fi
}
for query in "${QUERIES[@]}"; do
  java -Xmx8g -XX:ActiveProcessorCount=4 "-Dandroid.graph.path=$FIXTURE" \
    -cp "$(baseline_jar "$query")" "$ENTRY" "$query" record \
    "$OUTPUT/$query-oracle.tsv" "$OUTPUT/$query-oracle-raw.tsv" \
    > "$OUTPUT/$query-oracle.log" 2>&1
  cat "$OUTPUT/$query-oracle.tsv" >> "$OUTPUT/slow-warm-oracle.tsv"
done
for query in "${QUERIES[@]}"; do
  for pair in 1 2 3; do
    revisions=(candidate base)
    if [[ "$pair" == 2 ]]; then revisions=(base candidate); fi
    for revision in "${revisions[@]}"; do
      jar="$OUTPUT/candidate-slow-shapes.jar"
      if [[ "$revision" == base ]]; then jar=$(baseline_jar "$query"); fi
      stem="$query-$revision-$pair"
      started=$(node -p 'Date.now()')
      java -Xmx8g -XX:ActiveProcessorCount=4 "-Dandroid.graph.path=$FIXTURE" \
        -cp "$jar" "$ENTRY" "$query" verify "$OUTPUT/slow-warm-oracle.tsv" "$OUTPUT/$stem.tsv" \
        > "$OUTPUT/$stem.log" 2>&1
      finished=$(node -p 'Date.now()')
      jq -nc --arg queryName "$query" --arg revision "$revision" --argjson pair "$pair" \
        --argjson startedAt "$started" --argjson completedAt "$finished" \
        --arg jar "$(basename "$jar")" --arg jarSha256 "$(sha256sum "$jar" | cut -d' ' -f1)" \
        --arg rawFile "$stem.tsv" --arg rawSha256 "$(sha256sum "$OUTPUT/$stem.tsv" | cut -d' ' -f1)" \
        --arg logFile "$stem.log" --arg logSha256 "$(sha256sum "$OUTPUT/$stem.log" | cut -d' ' -f1)" \
        '{queryName:$queryName,revision:$revision,pair:$pair,startedAt:$startedAt,completedAt:$completedAt,exitCode:0,
          jar:$jar,jarSha256:$jarSha256,rawFile:$rawFile,rawSha256:$rawSha256,logFile:$logFile,logSha256:$logSha256}' \
        >> "$OUTPUT/slow-warm-runs.jsonl"
    done
    # The first pair checks integrity; a numerical suspect retains its evidence
    # while the mandatory reversed pair runs. No checkpoint can establish PASS.
    node "$FIXTURE_TOOL" fingerprint --fixture "$FIXTURE" --output "$OUTPUT/slow-shapes-fixture-after.json"
    seal_provenance
    node "$COMPARATOR" checkpoint --directory "$OUTPUT" --jars-directory "$OUTPUT" \
      --oracle "$OUTPUT/slow-warm-oracle.tsv" --provenance "$OUTPUT/slow-query-shapes-provenance.json" \
      --fixture-before "$OUTPUT/slow-shapes-fixture-before.json" \
      --fixture-after "$OUTPUT/slow-shapes-fixture-after.json" \
      --status "$OUTPUT/$query-checkpoint-$pair.json" --report "$OUTPUT/$query-checkpoint-$pair.md"
  done
done
