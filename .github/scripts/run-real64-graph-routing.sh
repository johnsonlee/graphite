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
OUTPUT_DIR=${6:-graph-routing-results}
FIXTURE_PROVENANCE=$(dirname "${MANIFEST}")/fixture-provenance.tsv
ORACLE=${OUTPUT_DIR}/base-single-source-oracle.manifest
TIMEOUT_MILLIS=${GRAPHITE_PRESSURE_TIMEOUT_MILLIS:-300000}
PUBLISH_EVIDENCE=${GRAPHITE_PRESSURE_PUBLISH_EVIDENCE:-true}
FILTER=io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
STATUS_CONTEXT=graphite/fixture64-graph-routing
HARNESS_PATH=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt
CORRECTNESS_MANIFEST_PATH=graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt
FIXTURE_VERIFIER_PATH=graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/Fixture64GraphPreparation.kt
COMPARATOR_PATH=.github/scripts/benchmark-gate.mjs
SCRIPT_PATH=.github/scripts/run-real64-graph-routing.sh
REPRODUCIBILITY_SCRIPT_PATH=.github/scripts/test-fixture64-reproducibility.sh
ZIP_HASHER_PATH=.github/scripts/canonical-zip-sha256.py
GIST_EVIDENCE_PATH=.github/scripts/gist-evidence.mjs
FIXTURE_PREPARATION_SCRIPT_PATH=.github/scripts/prepare-fixture64-graphs.sh
WORKLOAD_VERIFIER_PATH=.github/scripts/verify-fixture64-workload.sh
REPOSITORY_ROOT=$(git rev-parse --show-toplevel)
REPOSITORY_URL=$(git -C "${REPOSITORY_ROOT}" remote get-url origin)

for INPUT in "${MANIFEST}" "${FIXTURE_PROVENANCE}"; do
  test -f "${INPUT}"
done
test -d "${FIXTURE_JAR_DIR}"
[[ "${PUBLISH_EVIDENCE}" == true || "${PUBLISH_EVIDENCE}" == false ]]
[[ "${BASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-f]{40}$ ]]
test "$(grep -cv '^#' "${MANIFEST}")" -eq 64
test "$(tail -n +2 "${FIXTURE_PROVENANCE}" | wc -l | tr -d ' ')" -eq 64
test "$(cut -f2 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 4
test "$(cut -f16 "${FIXTURE_PROVENANCE}" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 64
while IFS=$'\t' read -r GRAPH_ID GRAPH_PATH _; do
  [[ "${GRAPH_ID}" == \#* ]] && continue
  test -f "${GRAPH_PATH}/graph.callsite-string-index"
  test -f "${GRAPH_PATH}/graph.callsite-trigram-prefilter"
done < "${MANIFEST}"
command -v java >/dev/null
command -v jq >/dev/null
command -v gh >/dev/null
command -v git >/dev/null
command -v python3 >/dev/null
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
test -f "${CANDIDATE_TREE}/${CORRECTNESS_MANIFEST_PATH}"
test -f "${CANDIDATE_TREE}/${FIXTURE_VERIFIER_PATH}"
test -f "${CANDIDATE_TREE}/${COMPARATOR_PATH}"
test -f "${CANDIDATE_TREE}/${SCRIPT_PATH}"
test -f "${CANDIDATE_TREE}/${REPRODUCIBILITY_SCRIPT_PATH}"
test -f "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}"
test -f "${CANDIDATE_TREE}/${GIST_EVIDENCE_PATH}"
test -f "${CANDIDATE_TREE}/${FIXTURE_PREPARATION_SCRIPT_PATH}"
test -f "${CANDIDATE_TREE}/${WORKLOAD_VERIFIER_PATH}"
cmp -s "$0" "${CANDIDATE_TREE}/${SCRIPT_PATH}"

# Build both production revisions with one byte-identical, candidate-reviewed pressure harness.
EXPECTED_BASE_INSTRUMENTATION=$(
  for INSTRUMENTATION_PATH in "${HARNESS_PATH}" "${CORRECTNESS_MANIFEST_PATH}"; do
    if ! cmp -s \
      "${BASE_TREE}/${INSTRUMENTATION_PATH}" \
      "${CANDIDATE_TREE}/${INSTRUMENTATION_PATH}"; then
      printf '%s\n' "${INSTRUMENTATION_PATH}"
    fi
  done | sort
)
cp "${CANDIDATE_TREE}/${HARNESS_PATH}" "${BASE_TREE}/${HARNESS_PATH}"
cp "${CANDIDATE_TREE}/${CORRECTNESS_MANIFEST_PATH}" "${BASE_TREE}/${CORRECTNESS_MANIFEST_PATH}"
cmp -s "${BASE_TREE}/${HARNESS_PATH}" "${CANDIDATE_TREE}/${HARNESS_PATH}"
cmp -s "${BASE_TREE}/${CORRECTNESS_MANIFEST_PATH}" "${CANDIDATE_TREE}/${CORRECTNESS_MANIFEST_PATH}"
test "$(git -C "${BASE_TREE}" diff --name-only | sort)" = "${EXPECTED_BASE_INSTRUMENTATION}"
test -z "$(git -C "${CANDIDATE_TREE}" diff --name-only)"

"${BASE_TREE}/gradlew" -p "${BASE_TREE}" :webgraph:jmhJar --no-daemon
"${CANDIDATE_TREE}/gradlew" -p "${CANDIDATE_TREE}" \
  :webgraph:jmhJar :webgraph:prepareBenchmarkFixtures --no-daemon
BASE_JAR=$(find "${BASE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
CANDIDATE_JAR=$(find "${CANDIDATE_TREE}/graphite-webgraph/build/libs" -maxdepth 1 -name '*-jmh.jar' -print -quit)
test -f "${BASE_JAR}"
test -f "${CANDIDATE_JAR}"

find_fixture_jar() {
  local directory=$1
  local pattern=$2
  local matches=()
  while IFS= read -r match; do
    matches+=("${match}")
  done < <(find "${directory}" -maxdepth 1 -type f -name "${pattern}" -print | sort)
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
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${CANDIDATE_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${MANIFEST}" "${FIXTURE_PROVENANCE}"

REPEATED_FIXTURE_OUTPUT=${BUILD_ROOT}/fixture64-repeat
"${CANDIDATE_TREE}/${REPRODUCIBILITY_SCRIPT_PATH}" \
  "${CANDIDATE_JAR}" "${PINNED_FIXTURE_DIR}" "$(dirname "${MANIFEST}")" \
  "${REPEATED_FIXTURE_OUTPUT}" "${OUTPUT_DIR}/fixture-reproducibility.json"

HARNESS_SHA256=$(sha256_file "${CANDIDATE_TREE}/${HARNESS_PATH}")
CORRECTNESS_MANIFEST_SHA256=$(sha256_file "${CANDIDATE_TREE}/${CORRECTNESS_MANIFEST_PATH}")
FIXTURE_VERIFIER_SHA256=$(sha256_file "${CANDIDATE_TREE}/${FIXTURE_VERIFIER_PATH}")
COMPARATOR_SHA256=$(sha256_file "${CANDIDATE_TREE}/${COMPARATOR_PATH}")
SCRIPT_SHA256=$(sha256_file "${CANDIDATE_TREE}/${SCRIPT_PATH}")
REPRODUCIBILITY_SCRIPT_SHA256=$(sha256_file "${CANDIDATE_TREE}/${REPRODUCIBILITY_SCRIPT_PATH}")
ZIP_HASHER_SHA256=$(sha256_file "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}")
GIST_EVIDENCE_SHA256=$(sha256_file "${CANDIDATE_TREE}/${GIST_EVIDENCE_PATH}")
FIXTURE_PREPARATION_SCRIPT_SHA256=$(sha256_file "${CANDIDATE_TREE}/${FIXTURE_PREPARATION_SCRIPT_PATH}")
WORKLOAD_VERIFIER_SHA256=$(sha256_file "${CANDIDATE_TREE}/${WORKLOAD_VERIFIER_PATH}")
BASE_JAR_CONTENT_SHA256=$(python3 "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}" "${BASE_JAR}")
CANDIDATE_JAR_CONTENT_SHA256=$(python3 "${CANDIDATE_TREE}/${ZIP_HASHER_PATH}" "${CANDIDATE_JAR}")
MANIFEST_SHA256=$(sha256_file "${MANIFEST}")
FIXTURE_PROVENANCE_SHA256=$(sha256_file "${FIXTURE_PROVENANCE}")

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
    -p timeoutMillis="${TIMEOUT_MILLIS}" -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json \
    -rff "${RESULT_PREFIX}.json" \
    -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=${MANIFEST} ${CORRECTNESS_ARGS} \
      -Dgraphite.broad.pressure.output=${RESULT_PREFIX}.correctness \
      -Dgraphite.broad.pressure.observations.output=${RESULT_PREFIX}.tsv"
}

# Build the correctness oracle from main's already-correct request-selected K-source paths.
# This is an executor reference, not an HTTP/API measurement. Keeping it separate from the graphId
# path makes correctness evidence independent of the optimization under test. The comparator validates
# all 64 single-source slots plus disjoint K=2/8/64 groups and expands their signatures to graphId identities.
BASE_REFERENCE_PREFIX=${OUTPUT_DIR}/base-single-source-reference
java -jar "${BASE_JAR}" "${FILTER}" \
  -p graphCount=64 -p coverageFamily=graph-routing-reference -p indexState=cold \
  -p timeoutMillis="${TIMEOUT_MILLIS}" -wi 0 -i 1 -f 1 -to 30m -foe true -rf json \
  -rff "${BASE_REFERENCE_PREFIX}.json" \
  -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=${MANIFEST} \
    -Dgraphite.broad.pressure.correctness.mode=record \
    -Dgraphite.broad.pressure.output=${BASE_REFERENCE_PREFIX}.manifest \
    -Dgraphite.broad.pressure.observations.output=${BASE_REFERENCE_PREFIX}.tsv"
node "${CANDIDATE_TREE}/${COMPARATOR_PATH}" derive-graph-routing-oracle \
  --references "${BASE_REFERENCE_PREFIX}.manifest" \
  --oracle "${ORACLE}"
test "$(wc -l < "${ORACLE}" | tr -d ' ')" -eq 1137
ORACLE_SHA256=$(sha256_file "${ORACLE}")

for INDEX_STATE in cold warm startup-prepared; do
  run_revision base "${INDEX_STATE}" "${BASE_JAR}" record ""
  run_revision candidate "${INDEX_STATE}" "${CANDIDATE_JAR}" verify "${ORACLE}"
done

for INDEX_STATE in cold warm startup-prepared; do
  case "${INDEX_STATE}" in
    cold) BASE_CORRECTNESS_ORACLE=${OUTPUT_DIR}/base-graph-routing-warm.correctness ;;
    warm|startup-prepared) BASE_CORRECTNESS_ORACLE=${OUTPUT_DIR}/base-graph-routing-cold.correctness ;;
  esac
  node "${CANDIDATE_TREE}/${COMPARATOR_PATH}" compare-graph-id-pressure \
    --base "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.json" \
    --candidate "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.json" \
    --base-observations "${OUTPUT_DIR}/base-graph-routing-${INDEX_STATE}.tsv" \
    --candidate-observations "${OUTPUT_DIR}/candidate-graph-routing-${INDEX_STATE}.tsv" \
    --base-correctness "${BASE_CORRECTNESS_ORACLE}" \
    --candidate-correctness "${ORACLE}" \
    --minimum-speedup 10 \
    --report "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-report.md" \
    --status "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json"
  jq -e '.passed == true' "${OUTPUT_DIR}/graph-routing-${INDEX_STATE}-status.json" >/dev/null
done

"${CANDIDATE_TREE}/${WORKLOAD_VERIFIER_PATH}" \
  "$(dirname "${MANIFEST}")" "$(dirname "${MANIFEST}")" \
  "${BASE_REFERENCE_PREFIX}.tsv" \
  "${BASE_REFERENCE_PREFIX}.manifest" \
  "${ORACLE}" \
  "${OUTPUT_DIR}/base-graph-routing-cold.tsv" \
  "${OUTPUT_DIR}/base-graph-routing-cold.correctness" \
  "${OUTPUT_DIR}/candidate-graph-routing-cold.tsv" \
  "${OUTPUT_DIR}/candidate-graph-routing-cold.correctness" \
  "${OUTPUT_DIR}/base-graph-routing-warm.tsv" \
  "${OUTPUT_DIR}/base-graph-routing-warm.correctness" \
  "${OUTPUT_DIR}/candidate-graph-routing-warm.tsv" \
  "${OUTPUT_DIR}/candidate-graph-routing-warm.correctness" \
  "${OUTPUT_DIR}/base-graph-routing-startup-prepared.tsv" \
  "${OUTPUT_DIR}/base-graph-routing-startup-prepared.correctness" \
  "${OUTPUT_DIR}/candidate-graph-routing-startup-prepared.tsv" \
  "${OUTPUT_DIR}/candidate-graph-routing-startup-prepared.correctness"

jq -n \
  --arg repository "${REPOSITORY}" \
  --arg baseSha "${BASE_SHA}" \
  --arg candidateSha "${CANDIDATE_SHA}" \
  --arg harnessSha256 "${HARNESS_SHA256}" \
  --arg correctnessManifestSha256 "${CORRECTNESS_MANIFEST_SHA256}" \
  --arg fixtureVerifierSha256 "${FIXTURE_VERIFIER_SHA256}" \
  --arg comparatorSha256 "${COMPARATOR_SHA256}" \
  --arg scriptSha256 "${SCRIPT_SHA256}" \
  --arg reproducibilityScriptSha256 "${REPRODUCIBILITY_SCRIPT_SHA256}" \
  --arg zipHasherSha256 "${ZIP_HASHER_SHA256}" \
  --arg gistEvidenceSha256 "${GIST_EVIDENCE_SHA256}" \
  --arg fixturePreparationScriptSha256 "${FIXTURE_PREPARATION_SCRIPT_SHA256}" \
  --arg workloadVerifierSha256 "${WORKLOAD_VERIFIER_SHA256}" \
  --arg baseJarContentSha256 "${BASE_JAR_CONTENT_SHA256}" \
  --arg candidateJarContentSha256 "${CANDIDATE_JAR_CONTENT_SHA256}" \
  --arg manifestSha256 "${MANIFEST_SHA256}" \
  --arg fixtureProvenanceSha256 "${FIXTURE_PROVENANCE_SHA256}" \
  --arg oracleSha256 "${ORACLE_SHA256}" \
  --arg oracleSource "base-single-source" \
  '{repository: $repository, baseSha: $baseSha, candidateSha: $candidateSha,
    harnessSha256: $harnessSha256, correctnessManifestSha256: $correctnessManifestSha256,
    fixtureVerifierSha256: $fixtureVerifierSha256,
    comparatorSha256: $comparatorSha256,
    scriptSha256: $scriptSha256, reproducibilityScriptSha256: $reproducibilityScriptSha256,
    zipHasherSha256: $zipHasherSha256, gistEvidenceSha256: $gistEvidenceSha256,
    fixturePreparationScriptSha256: $fixturePreparationScriptSha256,
    workloadVerifierSha256: $workloadVerifierSha256,
    baseJarContentSha256: $baseJarContentSha256,
    candidateJarContentSha256: $candidateJarContentSha256, manifestSha256: $manifestSha256,
    fixtureProvenanceSha256: $fixtureProvenanceSha256,
    oracleSha256: $oracleSha256, oracleSource: $oracleSource}' > "${OUTPUT_DIR}/provenance.json"

COLD_P50=$(jq -r '.gateP50Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
COLD_P95=$(jq -r '.gateP95Speedup' "${OUTPUT_DIR}/graph-routing-cold-status.json")
WARM_P50=$(jq -r '.gateP50Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
WARM_P95=$(jq -r '.gateP95Speedup' "${OUTPUT_DIR}/graph-routing-warm-status.json")
STARTUP_P50=$(jq -r '.gateP50Speedup' "${OUTPUT_DIR}/graph-routing-startup-prepared-status.json")
STARTUP_P95=$(jq -r '.gateP95Speedup' "${OUTPUT_DIR}/graph-routing-startup-prepared-status.json")
DESCRIPTION=$(printf 'fixture64 base=%.12s cold=%.2f/%.2fx warm=%.2f/%.2fx startup=%.2f/%.2fx correct=pass' \
  "${BASE_SHA}" "${COLD_P50}" "${COLD_P95}" "${WARM_P50}" "${WARM_P95}" \
  "${STARTUP_P50}" "${STARTUP_P95}")
test "${#DESCRIPTION}" -le 140

cp "${MANIFEST}" "${OUTPUT_DIR}/graphs.tsv"
cp "${FIXTURE_PROVENANCE}" "${OUTPUT_DIR}/fixture-provenance.tsv"
EVIDENCE_FILES=(
  "${OUTPUT_DIR}/provenance.json"
  "${OUTPUT_DIR}/graphs.tsv"
  "${OUTPUT_DIR}/fixture-provenance.tsv"
  "${OUTPUT_DIR}/fixture-reproducibility.json"
  "${OUTPUT_DIR}/base-single-source-reference.json"
  "${OUTPUT_DIR}/base-single-source-reference.tsv"
  "${OUTPUT_DIR}/base-single-source-reference.manifest"
  "${OUTPUT_DIR}/base-single-source-oracle.manifest"
  "${OUTPUT_DIR}/base-graph-routing-cold.json"
  "${OUTPUT_DIR}/base-graph-routing-cold.tsv"
  "${OUTPUT_DIR}/base-graph-routing-cold.correctness"
  "${OUTPUT_DIR}/candidate-graph-routing-cold.json"
  "${OUTPUT_DIR}/candidate-graph-routing-cold.tsv"
  "${OUTPUT_DIR}/candidate-graph-routing-cold.correctness"
  "${OUTPUT_DIR}/graph-routing-cold-report.md"
  "${OUTPUT_DIR}/graph-routing-cold-status.json"
  "${OUTPUT_DIR}/base-graph-routing-warm.json"
  "${OUTPUT_DIR}/base-graph-routing-warm.tsv"
  "${OUTPUT_DIR}/base-graph-routing-warm.correctness"
  "${OUTPUT_DIR}/candidate-graph-routing-warm.json"
  "${OUTPUT_DIR}/candidate-graph-routing-warm.tsv"
  "${OUTPUT_DIR}/candidate-graph-routing-warm.correctness"
  "${OUTPUT_DIR}/graph-routing-warm-report.md"
  "${OUTPUT_DIR}/graph-routing-warm-status.json"
  "${OUTPUT_DIR}/base-graph-routing-startup-prepared.json"
  "${OUTPUT_DIR}/base-graph-routing-startup-prepared.tsv"
  "${OUTPUT_DIR}/base-graph-routing-startup-prepared.correctness"
  "${OUTPUT_DIR}/candidate-graph-routing-startup-prepared.json"
  "${OUTPUT_DIR}/candidate-graph-routing-startup-prepared.tsv"
  "${OUTPUT_DIR}/candidate-graph-routing-startup-prepared.correctness"
  "${OUTPUT_DIR}/graph-routing-startup-prepared-report.md"
  "${OUTPUT_DIR}/graph-routing-startup-prepared-status.json"
)
FILES_JSON=$(
  for FILE in "${EVIDENCE_FILES[@]}"; do
    jq -n --arg name "$(basename "${FILE}")" --arg sha256 "$(sha256_file "${FILE}")" \
      '{key: $name, value: $sha256}'
  done | jq -s 'from_entries'
)
jq -n \
  --arg schema "graphite-fixture64-evidence-v8" \
  --arg repository "${REPOSITORY}" \
  --arg baseSha "${BASE_SHA}" \
  --arg candidateSha "${CANDIDATE_SHA}" \
  --arg statusContext "${STATUS_CONTEXT}" \
  --arg description "${DESCRIPTION}" \
  --argjson files "${FILES_JSON}" \
  '{schema: $schema, repository: $repository, baseSha: $baseSha, candidateSha: $candidateSha,
    statusContext: $statusContext, description: $description, files: $files}' \
  > "${OUTPUT_DIR}/evidence-manifest.json"
EVIDENCE_FILES+=("${OUTPUT_DIR}/evidence-manifest.json")
if [[ "${PUBLISH_EVIDENCE}" == false ]]; then
  echo "Completed trusted local ${STATUS_CONTEXT}: ${DESCRIPTION}"
  exit 0
fi
GIST_URL=$(gh gist create --public "${EVIDENCE_FILES[@]}" \
  --desc "Graphite fixture64 graph-routing evidence for ${CANDIDATE_SHA}")
GIST_ID=${GIST_URL##*/}
GIST_REVISION=$(gh api "gists/${GIST_ID}" --jq '.history[0].version')
GIST_OWNER=$(gh api "gists/${GIST_ID}" --jq '.owner.login')
test "${GIST_OWNER}" = "$(gh api user --jq .login)"
[[ "${GIST_REVISION}" =~ ^[0-9a-f]{40}$ ]]
EVIDENCE_URL="https://gist.github.com/${GIST_OWNER}/${GIST_ID}/revisions/${GIST_REVISION}"

gh api "repos/${REPOSITORY}/statuses/${CANDIDATE_SHA}" --method POST \
  -f state=success \
  -f context="${STATUS_CONTEXT}" \
  -f description="${DESCRIPTION}" \
  -f target_url="${EVIDENCE_URL}" >/dev/null

echo "Published ${STATUS_CONTEXT}: ${DESCRIPTION} evidence=${EVIDENCE_URL}"
