#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 6 ]]; then
  echo "Usage: $0 <fixture64-graphs.tsv> <reviewed-oracle> <base-sha> <candidate-sha>" \
    "[owner/repo] [output-dir]" >&2
  exit 2
fi

MANIFEST=$1
ORACLE=$2
BASE_SHA=$3
CANDIDATE_SHA=$4
REPOSITORY=${5:-johnsonlee/graphite}
OUTPUT_DIR=${6:-graph-routing-results}
FIXTURE_PROVENANCE=$(dirname "${MANIFEST}")/fixture-provenance.tsv
TIMEOUT_MILLIS=${GRAPHITE_PRESSURE_TIMEOUT_MILLIS:-300000}
FILTER=io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
STATUS_CONTEXT=graphite/fixture64-graph-routing
HARNESS_PATH=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt
COMPARATOR_PATH=.github/scripts/benchmark-gate.mjs
SCRIPT_PATH=.github/scripts/run-real64-graph-routing.sh
REPOSITORY_ROOT=$(git rev-parse --show-toplevel)
REPOSITORY_URL=$(git -C "${REPOSITORY_ROOT}" remote get-url origin)

for INPUT in "${MANIFEST}" "${ORACLE}" "${FIXTURE_PROVENANCE}"; do
  test -f "${INPUT}"
done
[[ "${BASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-f]{40}$ ]]
test "$(grep -cv '^#' "${MANIFEST}")" -eq 64
test "$(tail -n +2 "${FIXTURE_PROVENANCE}" | wc -l | tr -d ' ')" -eq 64
test "$(cut -f2 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 4
test "$(cut -f12 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 64
command -v java >/dev/null
command -v jq >/dev/null
command -v gh >/dev/null
command -v git >/dev/null
mkdir -p "${OUTPUT_DIR}"

sha256_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for REVISION_SHA in "${BASE_SHA}" "${CANDIDATE_SHA}"; do
  REMOTE_SHA=$(gh api "repos/${REPOSITORY}/commits/${REVISION_SHA}" --jq .sha)
  test "${REMOTE_SHA}" = "${REVISION_SHA}"
done

BUILD_ROOT=$(mktemp -d)
BASE_TREE=${BUILD_ROOT}/base
CANDIDATE_TREE=${BUILD_ROOT}/candidate
cleanup() {
  rm -rf "${BUILD_ROOT}"
}
trap cleanup EXIT

git clone --no-checkout "${REPOSITORY_URL}" "${BASE_TREE}" >/dev/null
git clone --no-checkout "${REPOSITORY_URL}" "${CANDIDATE_TREE}" >/dev/null
git -C "${BASE_TREE}" checkout --detach "${BASE_SHA}" >/dev/null
git -C "${CANDIDATE_TREE}" checkout --detach "${CANDIDATE_SHA}" >/dev/null
test "$(git -C "${BASE_TREE}" rev-parse HEAD)" = "${BASE_SHA}"
test "$(git -C "${CANDIDATE_TREE}" rev-parse HEAD)" = "${CANDIDATE_SHA}"
test -f "${CANDIDATE_TREE}/${HARNESS_PATH}"
test -f "${CANDIDATE_TREE}/${COMPARATOR_PATH}"
test -f "${CANDIDATE_TREE}/${SCRIPT_PATH}"
cmp -s "$0" "${CANDIDATE_TREE}/${SCRIPT_PATH}"

# Build both production revisions with one byte-identical, candidate-reviewed pressure harness.
cp "${CANDIDATE_TREE}/${HARNESS_PATH}" "${BASE_TREE}/${HARNESS_PATH}"
cmp -s "${BASE_TREE}/${HARNESS_PATH}" "${CANDIDATE_TREE}/${HARNESS_PATH}"
test "$(git -C "${BASE_TREE}" diff --name-only)" = "${HARNESS_PATH}"
test -z "$(git -C "${CANDIDATE_TREE}" diff --name-only)"

"${BASE_TREE}/gradlew" -p "${BASE_TREE}" :webgraph:jmhJar --no-daemon
"${CANDIDATE_TREE}/gradlew" -p "${CANDIDATE_TREE}" :webgraph:jmhJar --no-daemon
BASE_JAR=$(find "${BASE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
CANDIDATE_JAR=$(find "${CANDIDATE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
test -f "${BASE_JAR}"
test -f "${CANDIDATE_JAR}"

HARNESS_SHA256=$(sha256_file "${CANDIDATE_TREE}/${HARNESS_PATH}")
COMPARATOR_SHA256=$(sha256_file "${CANDIDATE_TREE}/${COMPARATOR_PATH}")
SCRIPT_SHA256=$(sha256_file "${CANDIDATE_TREE}/${SCRIPT_PATH}")
BASE_JAR_SHA256=$(sha256_file "${BASE_JAR}")
CANDIDATE_JAR_SHA256=$(sha256_file "${CANDIDATE_JAR}")
MANIFEST_SHA256=$(sha256_file "${MANIFEST}")
FIXTURE_PROVENANCE_SHA256=$(sha256_file "${FIXTURE_PROVENANCE}")
ORACLE_SHA256=$(sha256_file "${ORACLE}")

run_revision() {
  local REVISION=$1
  local INDEX_STATE=$2
  local JAR=$3
  local CORRECTNESS_MODE=$4
  local CORRECTNESS_INPUT=$5
  local RESULT_PREFIX="${OUTPUT_DIR}/${REVISION}-graph-routing-${INDEX_STATE}"
  local CORRECTNESS_ARGS
  if [[ "${CORRECTNESS_MODE}" == verify ]]; then
    CORRECTNESS_ARGS="-Dgraphite.broad.pressure.correctness.oracle=${CORRECTNESS_INPUT}"
  else
    CORRECTNESS_ARGS="-Dgraphite.broad.pressure.correctness.mode=record"
  fi
  java -jar "${JAR}" "${FILTER}" \
    -p graphCount=64 -p coverageFamily=graph-routing -p indexState="${INDEX_STATE}" \
    -p timeoutMillis="${TIMEOUT_MILLIS}" -wi 0 -i 1 -f 1 -foe true -prof gc -rf json \
    -rff "${RESULT_PREFIX}.json" \
    -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=${MANIFEST} ${CORRECTNESS_ARGS} \
      -Dgraphite.broad.pressure.output=${RESULT_PREFIX}.correctness \
      -Dgraphite.broad.pressure.observations.output=${RESULT_PREFIX}.tsv"
}

for INDEX_STATE in cold warm; do
  run_revision base "${INDEX_STATE}" "${BASE_JAR}" record ""
  run_revision candidate "${INDEX_STATE}" "${CANDIDATE_JAR}" verify "${ORACLE}"
  node "${CANDIDATE_TREE}/${COMPARATOR_PATH}" compare-graph-id-pressure \
    --base "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.json" \
    --candidate "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.json" \
    --base-observations "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.tsv" \
    --candidate-observations "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.tsv" \
    --minimum-speedup 10 \
    --report "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-report.md" \
    --status "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json"
  jq -e '.passed == true' "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json" >/dev/null
done

jq -n \
  --arg repository "${REPOSITORY}" \
  --arg baseSha "${BASE_SHA}" \
  --arg candidateSha "${CANDIDATE_SHA}" \
  --arg harnessSha256 "${HARNESS_SHA256}" \
  --arg comparatorSha256 "${COMPARATOR_SHA256}" \
  --arg scriptSha256 "${SCRIPT_SHA256}" \
  --arg baseJarSha256 "${BASE_JAR_SHA256}" \
  --arg candidateJarSha256 "${CANDIDATE_JAR_SHA256}" \
  --arg manifestSha256 "${MANIFEST_SHA256}" \
  --arg fixtureProvenanceSha256 "${FIXTURE_PROVENANCE_SHA256}" \
  --arg oracleSha256 "${ORACLE_SHA256}" \
  '{repository: $repository, baseSha: $baseSha, candidateSha: $candidateSha,
    harnessSha256: $harnessSha256, comparatorSha256: $comparatorSha256,
    scriptSha256: $scriptSha256, baseJarSha256: $baseJarSha256,
    candidateJarSha256: $candidateJarSha256, manifestSha256: $manifestSha256,
    fixtureProvenanceSha256: $fixtureProvenanceSha256,
    oracleSha256: $oracleSha256}' > "${OUTPUT_DIR}/provenance.json"

COLD_P50=$(jq -r '.gateP50Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
COLD_P95=$(jq -r '.gateP95Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
WARM_P50=$(jq -r '.gateP50Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
WARM_P95=$(jq -r '.gateP95Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
DESCRIPTION=$(printf 'fixture64 base=%.12s cold=%.2f/%.2fx warm=%.2f/%.2fx correct=pass' \
  "${BASE_SHA}" "${COLD_P50}" "${COLD_P95}" "${WARM_P50}" "${WARM_P95}")
test "${#DESCRIPTION}" -le 140

gh api "repos/${REPOSITORY}/statuses/${CANDIDATE_SHA}" --method POST \
  -f state=success \
  -f context="${STATUS_CONTEXT}" \
  -f description="${DESCRIPTION}" >/dev/null

echo "Published ${STATUS_CONTEXT}: ${DESCRIPTION}"
