#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 6 ]]; then
  echo "Usage: $0 <fixture64-graphs.tsv> <fixture-jar-directory> <base-sha> <candidate-sha>" \
    "[owner/repo] [output-dir]" >&2
  exit 2
fi

MANIFEST=$1
FIXTURE_JAR_DIR=$2
BASE_SHA=$3
CANDIDATE_SHA=$4
REPOSITORY=${5:-johnsonlee/graphite}
OUTPUT_DIR=${6:-global-wide-results}
FIXTURE_PROVENANCE=$(dirname "${MANIFEST}")/fixture-provenance.tsv
FILTER=io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
HARNESS_PATH=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt
CORRECTNESS_PATH=graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt
COMPARATOR_PATH=.github/scripts/benchmark-gate.mjs
SCRIPT_PATH=.github/scripts/run-real64-global-wide.sh
FIXTURE_VERIFIER_PATH=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/Fixture64GraphPreparation.kt
REPRODUCIBILITY_SCRIPT_PATH=.github/scripts/test-fixture64-reproducibility.sh
ZIP_HASHER_PATH=.github/scripts/canonical-zip-sha256.py
GIST_EVIDENCE_PATH=.github/scripts/gist-evidence.mjs
STATUS_CONTEXT=graphite/fixture64-global-wide
REPOSITORY_ROOT=$(git rev-parse --show-toplevel)
REPOSITORY_URL=$(git -C "${REPOSITORY_ROOT}" remote get-url origin)
TIMEOUT_MILLIS=${GRAPHITE_PRESSURE_TIMEOUT_MILLIS:-300000}
PUBLISH_EVIDENCE=${GRAPHITE_PRESSURE_PUBLISH_EVIDENCE:-true}

test -f "${MANIFEST}"
test -f "${FIXTURE_PROVENANCE}"
test -d "${FIXTURE_JAR_DIR}"
[[ "${PUBLISH_EVIDENCE}" == true || "${PUBLISH_EVIDENCE}" == false ]]
[[ "${BASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-f]{40}$ ]]
test "$(grep -cv '^#' "${MANIFEST}")" -eq 64
test "$(awk -F '\t' '!/^#/ { print $2 }' "${MANIFEST}" | sort -u | wc -l | tr -d ' ')" -eq 64
test "$(tail -n +2 "${FIXTURE_PROVENANCE}" | wc -l | tr -d ' ')" -eq 64
test "$(cut -f2 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 4
test "$(cut -f16 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 64
while IFS=$'\t' read -r GRAPH_ID GRAPH_PATH _; do
  [[ "${GRAPH_ID}" == \#* ]] && continue
  test -d "${GRAPH_PATH}"
  test -f "${GRAPH_PATH}/graph.callsite-string-index"
  test -f "${GRAPH_PATH}/graph.callsite-trigram-prefilter"
done < "${MANIFEST}"
command -v java >/dev/null
command -v jq >/dev/null
command -v node >/dev/null
command -v gh >/dev/null
command -v python3 >/dev/null
mkdir -p "${OUTPUT_DIR}"

sha256_file() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for REVISION_SHA in "${BASE_SHA}" "${CANDIDATE_SHA}"; do
  test "$(gh api "repos/${REPOSITORY}/commits/${REVISION_SHA}" --jq .sha)" = "${REVISION_SHA}"
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
test -f "${CANDIDATE_TREE}/${CORRECTNESS_PATH}"
test -f "${CANDIDATE_TREE}/${COMPARATOR_PATH}"
test -f "${CANDIDATE_TREE}/${SCRIPT_PATH}"
test -f "${CANDIDATE_TREE}/${FIXTURE_VERIFIER_PATH}"
test -f "${CANDIDATE_TREE}/${REPRODUCIBILITY_SCRIPT_PATH}"
test -f "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}"
test -f "${CANDIDATE_TREE}/${GIST_EVIDENCE_PATH}"
cmp -s "$0" "${CANDIDATE_TREE}/${SCRIPT_PATH}"

# Both production revisions are measured with the byte-identical candidate-reviewed harness.
cp "${CANDIDATE_TREE}/${HARNESS_PATH}" "${BASE_TREE}/${HARNESS_PATH}"
cp "${CANDIDATE_TREE}/${CORRECTNESS_PATH}" "${BASE_TREE}/${CORRECTNESS_PATH}"
cmp -s "${BASE_TREE}/${HARNESS_PATH}" "${CANDIDATE_TREE}/${HARNESS_PATH}"
cmp -s "${BASE_TREE}/${CORRECTNESS_PATH}" "${CANDIDATE_TREE}/${CORRECTNESS_PATH}"

"${BASE_TREE}/gradlew" -p "${BASE_TREE}" :webgraph:jmhJar --no-daemon
"${CANDIDATE_TREE}/gradlew" -p "${CANDIDATE_TREE}" \
  :webgraph:jmhJar :webgraph:prepareBenchmarkFixtures --no-daemon
BASE_JAR=$(find "${BASE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
CANDIDATE_JAR=$(find "${CANDIDATE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
test -f "${BASE_JAR}"
test -f "${CANDIDATE_JAR}"

find_fixture_jar() {
  local directory=$1 pattern=$2 matches=()
  while IFS= read -r match; do matches+=("${match}"); done \
    < <(find "${directory}" -maxdepth 1 -type f -name "${pattern}" -print | sort)
  test "${#matches[@]}" -eq 1
  printf '%s\n' "${matches[0]}"
}

PINNED_FIXTURE_DIR=${CANDIDATE_TREE}/graphite-webgraph/build/benchmark-fixtures
ANDROID_JAR=$(find_fixture_jar "${PINNED_FIXTURE_DIR}" 'android-all-*.jar')
TIKA_JAR=$(find_fixture_jar "${PINNED_FIXTURE_DIR}" 'tika-app-*.jar')
HIVE_JAR=$(find_fixture_jar "${PINNED_FIXTURE_DIR}" 'hive-exec-*.jar')
KOTLIN_JAR=$(find_fixture_jar "${PINNED_FIXTURE_DIR}" 'kotlin-compiler-embeddable-*.jar')
for PINNED_JAR in "${ANDROID_JAR}" "${TIKA_JAR}" "${HIVE_JAR}" "${KOTLIN_JAR}"; do
  SUPPLIED_JAR=$(find_fixture_jar "${FIXTURE_JAR_DIR}" "$(basename "${PINNED_JAR}")")
  cmp -s "${SUPPLIED_JAR}" "${PINNED_JAR}"
done
java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${CANDIDATE_JAR}" io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${MANIFEST}" "${FIXTURE_PROVENANCE}"
REPEATED_FIXTURE_OUTPUT=${BUILD_ROOT}/fixture64-repeat
"${CANDIDATE_TREE}/${REPRODUCIBILITY_SCRIPT_PATH}" \
  "${CANDIDATE_JAR}" "${PINNED_FIXTURE_DIR}" "$(dirname "${MANIFEST}")" \
  "${REPEATED_FIXTURE_OUTPUT}" "${OUTPUT_DIR}/fixture-reproducibility.json"

run_pressure() {
  local JAR=$1
  local PREFIX=$2
  local CORRECTNESS_ARGS=$3
  java -jar "${JAR}" "${FILTER}" \
    -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold \
    -p timeoutMillis="${TIMEOUT_MILLIS}" -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json \
    -rff "${PREFIX}.json" \
    -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=${MANIFEST} ${CORRECTNESS_ARGS} \
      -Dgraphite.broad.pressure.observations.output=${PREFIX}.tsv"
}

ORACLE_PREFIX=${OUTPUT_DIR}/base-global-wide-oracle-seed
ORACLE=${ORACLE_PREFIX}.correctness
run_pressure "${BASE_JAR}" "${ORACLE_PREFIX}" \
  "-Dgraphite.broad.pressure.correctness.mode=record -Dgraphite.broad.pressure.output=${ORACLE}"
test "$(grep -cv '^#' "${ORACLE}")" -eq 34

BASE_JSON_FILES=()
BASE_OBSERVATION_FILES=()
CANDIDATE_JSON_FILES=()
CANDIDATE_OBSERVATION_FILES=()
for RUN in 1 2 3; do
  BASE_PREFIX=${OUTPUT_DIR}/base-global-wide-${RUN}
  CANDIDATE_PREFIX=${OUTPUT_DIR}/candidate-global-wide-${RUN}
  run_base() {
    run_pressure "${BASE_JAR}" "${BASE_PREFIX}" \
      "-Dgraphite.broad.pressure.correctness.mode=verify \
        -Dgraphite.broad.pressure.correctness.oracle=${ORACLE}"
  }
  run_candidate() {
    run_pressure "${CANDIDATE_JAR}" "${CANDIDATE_PREFIX}" \
      "-Dgraphite.broad.pressure.correctness.mode=verify \
        -Dgraphite.broad.pressure.correctness.oracle=${ORACLE}"
  }
  # Reverse the pair order on alternating runs. The gate compares corresponding pairs, so a
  # base-first-only page-cache advantage cannot establish the required speedup.
  if (( RUN % 2 == 1 )); then run_candidate; run_base; else run_base; run_candidate; fi
  BASE_JSON_FILES+=("${BASE_PREFIX}.json")
  BASE_OBSERVATION_FILES+=("${BASE_PREFIX}.tsv")
  CANDIDATE_JSON_FILES+=("${CANDIDATE_PREFIX}.json")
  CANDIDATE_OBSERVATION_FILES+=("${CANDIDATE_PREFIX}.tsv")
done

IFS=, BASE_JSON_LIST="${BASE_JSON_FILES[*]}"
IFS=, BASE_OBSERVATION_LIST="${BASE_OBSERVATION_FILES[*]}"
IFS=, CANDIDATE_JSON_LIST="${CANDIDATE_JSON_FILES[*]}"
IFS=, CANDIDATE_OBSERVATION_LIST="${CANDIDATE_OBSERVATION_FILES[*]}"
node "${CANDIDATE_TREE}/${COMPARATOR_PATH}" compare-global-wide-pressure \
  --bases "${BASE_JSON_LIST}" \
  --candidates "${CANDIDATE_JSON_LIST}" \
  --base-observations "${BASE_OBSERVATION_LIST}" \
  --candidate-observations "${CANDIDATE_OBSERVATION_LIST}" \
  --run-orders candidate-base,base-candidate,candidate-base \
  --graph-manifest "${MANIFEST}" \
  --correctness-oracle "${ORACLE}" \
  --minimum-speedup 5 \
  --report "${OUTPUT_DIR}/global-wide-report.md" \
  --status "${OUTPUT_DIR}/global-wide-status.json"
jq -e '.passed == true' "${OUTPUT_DIR}/global-wide-status.json" >/dev/null

cp "${MANIFEST}" "${OUTPUT_DIR}/graphs.tsv"
cp "${FIXTURE_PROVENANCE}" "${OUTPUT_DIR}/fixture-provenance.tsv"
HARNESS_SHA256=$(sha256_file "${CANDIDATE_TREE}/${HARNESS_PATH}")
CORRECTNESS_SHA256=$(sha256_file "${CANDIDATE_TREE}/${CORRECTNESS_PATH}")
FIXTURE_VERIFIER_SHA256=$(sha256_file "${CANDIDATE_TREE}/${FIXTURE_VERIFIER_PATH}")
COMPARATOR_SHA256=$(sha256_file "${CANDIDATE_TREE}/${COMPARATOR_PATH}")
SCRIPT_SHA256=$(sha256_file "${CANDIDATE_TREE}/${SCRIPT_PATH}")
BASE_JAR_SHA256=$(python3 "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}" "${BASE_JAR}")
CANDIDATE_JAR_SHA256=$(python3 "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}" "${CANDIDATE_JAR}")
jq -n \
  --arg repository "${REPOSITORY}" --arg baseSha "${BASE_SHA}" --arg candidateSha "${CANDIDATE_SHA}" \
  --arg harnessSha256 "${HARNESS_SHA256}" --arg correctnessSha256 "${CORRECTNESS_SHA256}" \
  --arg fixtureVerifierSha256 "${FIXTURE_VERIFIER_SHA256}" \
  --arg comparatorSha256 "${COMPARATOR_SHA256}" --arg scriptSha256 "${SCRIPT_SHA256}" \
  --arg baseJarContentSha256 "${BASE_JAR_SHA256}" \
  --arg candidateJarContentSha256 "${CANDIDATE_JAR_SHA256}" \
  --arg manifestSha256 "$(sha256_file "${MANIFEST}")" \
  --arg fixtureProvenanceSha256 "$(sha256_file "${FIXTURE_PROVENANCE}")" \
  --arg oracleSha256 "$(sha256_file "${ORACLE}")" \
  '{repository:$repository,baseSha:$baseSha,candidateSha:$candidateSha,
    harnessSha256:$harnessSha256,correctnessSha256:$correctnessSha256,
    fixtureVerifierSha256:$fixtureVerifierSha256,comparatorSha256:$comparatorSha256,
    scriptSha256:$scriptSha256,baseJarContentSha256:$baseJarContentSha256,
    candidateJarContentSha256:$candidateJarContentSha256,manifestSha256:$manifestSha256,
    fixtureProvenanceSha256:$fixtureProvenanceSha256,oracleSha256:$oracleSha256,
    fixtureSource:"four-pinned-fixture-jars",runOrder:"candidate-base,base-candidate,candidate-base"}' \
  > "${OUTPUT_DIR}/provenance.json"

WORST_P50=$(jq '[.runs[].p50Speedup] | min' "${OUTPUT_DIR}/global-wide-status.json")
WORST_P95=$(jq '[.runs[].p95Speedup] | min' "${OUTPUT_DIR}/global-wide-status.json")
DESCRIPTION=$(printf 'fixture64-wide base=%.12s p50=%.2fx p95=%.2fx pairs=3 correct=pass' \
  "${BASE_SHA}" "${WORST_P50}" "${WORST_P95}")
test "${#DESCRIPTION}" -le 140
EVIDENCE_FILES=(
  "${OUTPUT_DIR}/provenance.json" "${OUTPUT_DIR}/graphs.tsv"
  "${OUTPUT_DIR}/fixture-provenance.tsv" "${OUTPUT_DIR}/fixture-reproducibility.json"
  "${ORACLE}" "${OUTPUT_DIR}/global-wide-report.md" "${OUTPUT_DIR}/global-wide-status.json"
  "${BASE_JSON_FILES[@]}" "${BASE_OBSERVATION_FILES[@]}"
  "${CANDIDATE_JSON_FILES[@]}" "${CANDIDATE_OBSERVATION_FILES[@]}"
)
FILES_JSON=$(for FILE in "${EVIDENCE_FILES[@]}"; do
  jq -n --arg name "$(basename "${FILE}")" --arg sha256 "$(sha256_file "${FILE}")" \
    '{key:$name,value:$sha256}'
done | jq -s from_entries)
jq -n --arg schema graphite-fixture64-global-wide-evidence-v2 --arg repository "${REPOSITORY}" \
  --arg baseSha "${BASE_SHA}" --arg candidateSha "${CANDIDATE_SHA}" \
  --arg statusContext "${STATUS_CONTEXT}" --arg description "${DESCRIPTION}" \
  --argjson files "${FILES_JSON}" \
  '{schema:$schema,repository:$repository,baseSha:$baseSha,candidateSha:$candidateSha,
    statusContext:$statusContext,description:$description,files:$files}' \
  > "${OUTPUT_DIR}/evidence-manifest.json"
EVIDENCE_FILES+=("${OUTPUT_DIR}/evidence-manifest.json")
if [[ "${PUBLISH_EVIDENCE}" == false ]]; then
  echo "Produced trusted local global-wide evidence in ${OUTPUT_DIR}: ${DESCRIPTION}"
  exit 0
fi
GIST_URL=$(gh gist create --public "${EVIDENCE_FILES[@]}" \
  --desc "Graphite fixture64 global-wide evidence for ${CANDIDATE_SHA}")
GIST_ID=${GIST_URL##*/}
GIST_REVISION=$(gh api "gists/${GIST_ID}" --jq '.history[0].version')
GIST_OWNER=$(gh api "gists/${GIST_ID}" --jq '.owner.login')
[[ "${GIST_REVISION}" =~ ^[0-9a-f]{40}$ ]]
EVIDENCE_URL="https://gist.github.com/${GIST_OWNER}/${GIST_ID}/revisions/${GIST_REVISION}"
gh api "repos/${REPOSITORY}/statuses/${CANDIDATE_SHA}" --method POST \
  -f state=success -f context="${STATUS_CONTEXT}" -f description="${DESCRIPTION}" \
  -f target_url="${EVIDENCE_URL}" >/dev/null
echo "Published ${STATUS_CONTEXT}: ${DESCRIPTION} evidence=${EVIDENCE_URL}"
