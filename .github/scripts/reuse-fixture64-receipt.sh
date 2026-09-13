#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <webgraph-jmh.jar> <fixture-jar-directory> <graphs-directory> <receipt> <expected-input-key>" >&2
  exit 2
fi

JMH_JAR=$1
FIXTURE_DIR=$2
GRAPH_DIR=$3
RECEIPT=$4
INPUT_KEY=$5
MANIFEST=${GRAPH_DIR}/graphs.tsv
PROVENANCE=${GRAPH_DIR}/fixture-provenance.tsv

test -f "${JMH_JAR}"
test -d "${FIXTURE_DIR}"
test -f "${MANIFEST}"
test -f "${PROVENANCE}"
test -f "${RECEIPT}"
test -n "${INPUT_KEY}"

# Only an exact, input-key-bound cached proof can replace another generator self-test run.
# This never creates a receipt or changes its input key/claims.
jq -e --arg inputKey "${INPUT_KEY}" \
  '.passed == true and .inputKey == $inputKey' "${RECEIPT}" >/dev/null

sha256_stream() {
  sha256sum | awk '{print $1}'
}
PROVENANCE_SHA=$(cut -f1-18 "${PROVENANCE}" | sha256_stream)
MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next }
  { print $1, $3, $4, $5, $6 }' "${MANIFEST}" | sha256_stream)
jq -e --arg provenance "${PROVENANCE_SHA}" --arg manifest "${MANIFEST_SHA}" \
  '.firstProvenanceSha256 == $provenance and .repeatedProvenanceSha256 == $provenance and
   .firstManifestSemanticSha256 == $manifest and .repeatedManifestSemanticSha256 == $manifest' \
  "${RECEIPT}" >/dev/null

# Rebind only absolute locations after restoring into another runner workspace.
GRAPH_ROOT=$(cd "${GRAPH_DIR}" && pwd)
MANIFEST_TMP=$(mktemp)
PROVENANCE_TMP=$(mktemp)
trap 'rm -f "${MANIFEST_TMP}" "${PROVENANCE_TMP}"' EXIT
awk -F '\t' -v OFS='\t' -v root="${GRAPH_ROOT}" \
  '/^#/ { print; next } { $2=root "/" $1; print }' "${MANIFEST}" > "${MANIFEST_TMP}"
awk -F '\t' -v OFS='\t' -v root="${GRAPH_ROOT}" \
  'NR == 1 { print; next } { $19=root "/" $1; print }' "${PROVENANCE}" > "${PROVENANCE_TMP}"
mv "${MANIFEST_TMP}" "${MANIFEST}"
mv "${PROVENANCE_TMP}" "${PROVENANCE}"

find_one() {
  local pattern=$1
  local matches=()
  while IFS= read -r match; do matches+=("${match}"); done \
    < <(find "${FIXTURE_DIR}" -maxdepth 1 -type f -name "${pattern}" -print | sort)
  test "${#matches[@]}" -eq 1
  printf '%s\n' "${matches[0]}"
}
ANDROID_JAR=$(find_one 'android-all-*.jar')
TIKA_JAR=$(find_one 'tika-app-*.jar')
HIVE_JAR=$(find_one 'hive-exec-*.jar')
KOTLIN_JAR=$(find_one 'kotlin-compiler-embeddable-*.jar')

# A cached receipt proves the generator contract, never the integrity of today's restored files.
# Always recheck all graph content, source-JAR identities and provenance with the current verifier.
java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${MANIFEST}" "${PROVENANCE}"

echo "Reused fixture64 generator proof for ${INPUT_KEY}; restored content independently verified"
