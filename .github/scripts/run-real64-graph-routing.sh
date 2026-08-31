#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 7 || $# -gt 9 ]]; then
  echo "Usage: $0 <graphs.tsv> <reviewed-oracle> <base-jmh.jar> <candidate-jmh.jar>" \
    "<base-sha> <candidate-sha> <https-evidence-url> [owner/repo] [output-dir]" >&2
  exit 2
fi

MANIFEST=$1
ORACLE=$2
BASE_JAR=$3
CANDIDATE_JAR=$4
BASE_SHA=$5
CANDIDATE_SHA=$6
EVIDENCE_URL=$7
REPOSITORY=${8:-johnsonlee/graphite}
OUTPUT_DIR=${9:-graph-routing-results}
TIMEOUT_MILLIS=${GRAPHITE_PRESSURE_TIMEOUT_MILLIS:-300000}
FILTER=io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
STATUS_CONTEXT=graphite/real64-graph-routing

for INPUT in "${MANIFEST}" "${ORACLE}" "${BASE_JAR}" "${CANDIDATE_JAR}"; do
  test -f "${INPUT}"
done
[[ "${BASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-f]{40}$ ]]
[[ "${EVIDENCE_URL}" == https://* ]]
command -v java >/dev/null
command -v jq >/dev/null
command -v gh >/dev/null
mkdir -p "${OUTPUT_DIR}"

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
    -jvmArgsAppend "-Dgraphite.broad.pressure.graphs=${MANIFEST} ${CORRECTNESS_ARGS} \
      -Dgraphite.broad.pressure.output=${RESULT_PREFIX}.correctness \
      -Dgraphite.broad.pressure.observations.output=${RESULT_PREFIX}.tsv"
}

for INDEX_STATE in cold warm; do
  run_revision base "${INDEX_STATE}" "${BASE_JAR}" record ""
  run_revision candidate "${INDEX_STATE}" "${CANDIDATE_JAR}" verify "${ORACLE}"
  node .github/scripts/benchmark-gate.mjs compare-graph-id-pressure \
    --base "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.json" \
    --candidate "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.json" \
    --base-observations "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.tsv" \
    --candidate-observations "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.tsv" \
    --minimum-speedup 10 \
    --report "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-report.md" \
    --status "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json"
  jq -e '.passed == true' "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json" >/dev/null
done

COLD_P50=$(jq -r '.p50Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
COLD_P95=$(jq -r '.p95Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
WARM_P50=$(jq -r '.p50Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
WARM_P95=$(jq -r '.p95Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
DESCRIPTION=$(printf 'real64 base=%.12s cold=%.2f/%.2fx warm=%.2f/%.2fx correct=pass' \
  "${BASE_SHA}" "${COLD_P50}" "${COLD_P95}" "${WARM_P50}" "${WARM_P95}")
test "${#DESCRIPTION}" -le 140

gh api "repos/${REPOSITORY}/statuses/${CANDIDATE_SHA}" --method POST \
  -f state=success \
  -f context="${STATUS_CONTEXT}" \
  -f description="${DESCRIPTION}" \
  -f target_url="${EVIDENCE_URL}" >/dev/null

echo "Published ${STATUS_CONTEXT}: ${DESCRIPTION}"
