#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 6 ]]; then
  echo 'Usage: run-wide-latency-shard.sh <bundle> <shared-fixture64> <shard> <base-sha> <candidate-sha> <output>' >&2
  exit 2
fi
BUNDLE=$(cd "$1" && pwd)
SHARED=$(cd "$2" && pwd)
SHARD=$3
BASE_SHA=$4
CANDIDATE_SHA=$5
test ! -e "$6"
mkdir -p "$6"
OUTPUT=$(cd "$6" && pwd)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
[[ "$SHARD" == standard || "$SHARD" == full-scan ]]
node "$SCRIPT_DIR/benchmark-wide-shards.mjs" verify-build --directory "$BUNDLE" --base "$BASE_SHA" --candidate "$CANDIDATE_SHA"
"$SCRIPT_DIR/verify-shared-fixture64.sh" "$BUNDLE/candidate.jar" "$BUNDLE/fixtures" "$SHARED" "$CANDIDATE_SHA"
cmp "$BUNDLE/graphs.tsv" "$SHARED/graphs/graphs.tsv"
cmp "$BUNDLE/fixture-provenance.tsv" "$SHARED/graphs/fixture-provenance.tsv"
cmp "$BUNDLE/fixture-reproducibility.json" "$SHARED/fixture-reproducibility.json"
cmp "$BUNDLE/fixture64.complete.json" "$SHARED/fixture64.complete.json"
cp "$BUNDLE/build.json" "$OUTPUT/build.json"
export SHARD BASE_SHA CANDIDATE_SHA OUTPUT
node --input-type=module <<'JS'
import fs from 'node:fs';
import os from 'node:os';
const env = process.env;
fs.writeFileSync(`${env.OUTPUT}/runner.json`, JSON.stringify({
  runId: env.GITHUB_RUN_ID, runAttempt: env.GITHUB_RUN_ATTEMPT,
  job: env.GITHUB_JOB, runnerName: env.RUNNER_NAME, hostname: os.hostname(),
  shard: env.SHARD, baseSha: env.BASE_SHA, candidateSha: env.CANDIDATE_SHA,
  order: ['candidate-base', 'base-candidate', 'candidate-base']
}, null, 2));
JS
# Every query's three pairs stay on this runner. No overlapping base/candidate JVMs.
for FORK in 1 2 3; do
  ORDER=(candidate base)
  if [[ "$FORK" == 2 ]]; then ORDER=(base candidate); fi
  for REVISION in "${ORDER[@]}"; do
    java -jar "$BUNDLE/$REVISION.jar" LargeBroadQueryPressureBenchmark.replayBroadQueries \
      -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000 \
      -wi 0 -i 1 -f 1 -to 90m -foe true -rf json -rff "$OUTPUT/$REVISION-$FORK.json" \
      -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=$SHARED/graphs/graphs.tsv \
        -Dgraphite.broad.pressure.correctness.mode=verify \
        -Dgraphite.broad.pressure.latency.only=true \
        -Dgraphite.broad.pressure.latency.shard=$SHARD \
        -Dgraphite.broad.pressure.latency.oracle=$BUNDLE/oracle.correctness \
        -Dgraphite.broad.pressure.latency.output=$OUTPUT/$REVISION-$FORK.tsv"
  done
  # A completed pair's regression, or an observed cross-pair spread, cannot be repaired
  # by later forks. Preserve raw evidence and an explicit failed checkpoint before stopping.
  node "$SCRIPT_DIR/benchmark-wide-shards.mjs" check-progress \
    --directory "$OUTPUT" --bundle "$BUNDLE" --shard "$SHARD" --pairs "$FORK"
done
node "$SCRIPT_DIR/benchmark-wide-shards.mjs" seal-shard --directory "$OUTPUT" --bundle "$BUNDLE" --shard "$SHARD"
