#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo "Usage: $0 <webgraph-jmh.jar> <fixture-jar-directory> <prepared-output> <repeat-output> [receipt]" >&2
  exit 2
fi

JMH_JAR=$1
FIXTURE_DIR=$2
FIRST_OUTPUT=$3
SECOND_OUTPUT=$4
RECEIPT=${5:-}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

test -f "${JMH_JAR}"
test -d "${FIXTURE_DIR}"
test -f "${FIRST_OUTPUT}/graphs.tsv"
test -f "${FIRST_OUTPUT}/fixture-provenance.tsv"
test ! -e "${SECOND_OUTPUT}"

"${SCRIPT_DIR}/prepare-fixture64-graphs.sh" "${JMH_JAR}" "${FIXTURE_DIR}" "${SECOND_OUTPUT}"

diff -u \
  <(cut -f1-13 "${FIRST_OUTPUT}/fixture-provenance.tsv") \
  <(cut -f1-13 "${SECOND_OUTPUT}/fixture-provenance.tsv")
diff -u \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
    "${FIRST_OUTPUT}/graphs.tsv") \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
    "${SECOND_OUTPUT}/graphs.tsv")

find_one() {
  local pattern=$1
  local matches=()
  while IFS= read -r match; do
    matches+=("${match}")
  done < <(find "${FIXTURE_DIR}" -maxdepth 1 -type f -name "${pattern}" -print | sort)
  test "${#matches[@]}" -eq 1
  printf '%s\n' "${matches[0]}"
}

ANDROID_JAR=$(find_one 'android-all-*.jar')
TIKA_JAR=$(find_one 'tika-app-*.jar')
HIVE_JAR=$(find_one 'hive-exec-*.jar')
KOTLIN_JAR=$(find_one 'kotlin-compiler-embeddable-*.jar')
java -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --self-test-order-fingerprint
TAMPERED_PROVENANCE=$(mktemp)
MODIFIED_FIXTURE_DIR=$(mktemp -d)
trap 'rm -f "${TAMPERED_PROVENANCE}"; rm -rf "${MODIFIED_FIXTURE_DIR}"' EXIT
awk -F '\t' 'BEGIN { OFS="\t" } NR == 2 { $13=sprintf("%064d", 0) } { print }' \
  "${FIRST_OUTPUT}/fixture-provenance.tsv" > "${TAMPERED_PROVENANCE}"

if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${FIRST_OUTPUT}/graphs.tsv" "${TAMPERED_PROVENANCE}"; then
  echo "Tampered fixture64 provenance unexpectedly passed verification" >&2
  exit 1
fi

MODIFIED_ANDROID_JAR=${MODIFIED_FIXTURE_DIR}/$(basename "${ANDROID_JAR}")
cp "${ANDROID_JAR}" "${MODIFIED_ANDROID_JAR}"
printf '\0' >> "${MODIFIED_ANDROID_JAR}"
if java -Xmx4g \
  -Dandroid.jar.path="${MODIFIED_ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${FIRST_OUTPUT}/graphs.tsv" "${FIRST_OUTPUT}/fixture-provenance.tsv"; then
  echo "Substituted fixture JAR unexpectedly passed verification" >&2
  exit 1
fi

sha256_stream() {
  if command -v sha256sum >/dev/null; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

FIRST_PROVENANCE_SHA=$(cut -f1-13 "${FIRST_OUTPUT}/fixture-provenance.tsv" | sha256_stream)
SECOND_PROVENANCE_SHA=$(cut -f1-13 "${SECOND_OUTPUT}/fixture-provenance.tsv" | sha256_stream)
FIRST_MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
  "${FIRST_OUTPUT}/graphs.tsv" | sha256_stream)
SECOND_MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
  "${SECOND_OUTPUT}/graphs.tsv" | sha256_stream)

if [[ -n "${RECEIPT}" ]]; then
  jq -n \
    --argjson passed true \
    --arg firstProvenanceSha256 "${FIRST_PROVENANCE_SHA}" \
    --arg repeatedProvenanceSha256 "${SECOND_PROVENANCE_SHA}" \
    --arg firstManifestSemanticSha256 "${FIRST_MANIFEST_SHA}" \
    --arg repeatedManifestSemanticSha256 "${SECOND_MANIFEST_SHA}" \
    '{passed: $passed, firstProvenanceSha256: $firstProvenanceSha256,
      repeatedProvenanceSha256: $repeatedProvenanceSha256,
      firstManifestSemanticSha256: $firstManifestSemanticSha256,
      repeatedManifestSemanticSha256: $repeatedManifestSemanticSha256}' > "${RECEIPT}"
fi

echo "Fixture64 identities are reproducible and tampered provenance is rejected"
