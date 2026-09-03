#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <webgraph-jmh.jar> <fixture-jar-directory> <artifact-directory> <candidate-sha>" >&2
  exit 2
fi

JMH_JAR=$1
FIXTURE_DIR=$2
ARTIFACT_DIR=$3
CANDIDATE_SHA=$4
MANIFEST=${ARTIFACT_DIR}/graphs/graphs.tsv
PROVENANCE=${ARTIFACT_DIR}/graphs/fixture-provenance.tsv
RECEIPT=${ARTIFACT_DIR}/fixture-reproducibility.json
MARKER=${ARTIFACT_DIR}/fixture64.complete.json

test -f "${JMH_JAR}"
test -d "${FIXTURE_DIR}"
test -f "${MANIFEST}"
test -f "${PROVENANCE}"
test -f "${RECEIPT}"
test -f "${MARKER}"
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-f]{40}$ ]]

find_one() {
  local pattern=$1
  local matches=()
  while IFS= read -r match; do
    matches+=("${match}")
  done < <(find "${FIXTURE_DIR}" -maxdepth 1 -type f -name "${pattern}" -print | sort)
  test "${#matches[@]}" -eq 1
  printf '%s\n' "${matches[0]}"
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

sha256_stream() {
  sha256sum | awk '{print $1}'
}

ANDROID_JAR=$(find_one 'android-all-*.jar')
TIKA_JAR=$(find_one 'tika-app-*.jar')
HIVE_JAR=$(find_one 'hive-exec-*.jar')
KOTLIN_JAR=$(find_one 'kotlin-compiler-embeddable-*.jar')
FIXTURE_JAR_SET_SHA=$(
  for FIXTURE_JAR in "${ANDROID_JAR}" "${TIKA_JAR}" "${HIVE_JAR}" "${KOTLIN_JAR}"; do
    printf '%s\t%s\n' "$(basename "${FIXTURE_JAR}")" "$(sha256_file "${FIXTURE_JAR}")"
  done | sort | sha256_stream
)
MANIFEST_SHA=$(sha256_file "${MANIFEST}")
PROVENANCE_SHA=$(sha256_file "${PROVENANCE}")
RECEIPT_SHA=$(sha256_file "${RECEIPT}")

jq -e \
  --arg candidateSha "${CANDIDATE_SHA}" \
  --arg fixtureJarSetSha256 "${FIXTURE_JAR_SET_SHA}" \
  --arg manifestSha256 "${MANIFEST_SHA}" \
  --arg provenanceSha256 "${PROVENANCE_SHA}" \
  --arg receiptSha256 "${RECEIPT_SHA}" \
  '.schema == "graphite-shared-fixture64-v1" and .complete == true and
   .candidateSha == $candidateSha and .fixtureJarSetSha256 == $fixtureJarSetSha256 and
   .manifestSha256 == $manifestSha256 and .provenanceSha256 == $provenanceSha256 and
   .receiptSha256 == $receiptSha256' "${MARKER}" >/dev/null

NORMALIZED_PROVENANCE_SHA=$(cut -f1-20 "${PROVENANCE}" | sha256_stream)
NORMALIZED_MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next }
  { print $1, $3, $4, $5, $6 }' "${MANIFEST}" | sha256_stream)
jq -e \
  --arg provenance "${NORMALIZED_PROVENANCE_SHA}" \
  --arg manifest "${NORMALIZED_MANIFEST_SHA}" \
  '.passed == true and .firstProvenanceSha256 == $provenance and
   .repeatedProvenanceSha256 == $provenance and
   .firstManifestSemanticSha256 == $manifest and
   .repeatedManifestSemanticSha256 == $manifest' "${RECEIPT}" >/dev/null

java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${MANIFEST}" "${PROVENANCE}"

echo "Verified shared fixture64 artifact for ${CANDIDATE_SHA}"
