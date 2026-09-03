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

if [[ ! -e "${SECOND_OUTPUT}" ]]; then
  "${SCRIPT_DIR}/prepare-fixture64-graphs.sh" "${JMH_JAR}" "${FIXTURE_DIR}" "${SECOND_OUTPUT}"
fi
test -f "${SECOND_OUTPUT}/graphs.tsv"
test -f "${SECOND_OUTPUT}/fixture-provenance.tsv"

relocate_output() {
  local output=$1
  local output_root manifest_tmp provenance_tmp
  output_root=$(cd "${output}" && pwd)
  manifest_tmp=$(mktemp)
  provenance_tmp=$(mktemp)
  awk -F '\t' -v OFS='\t' -v root="${output_root}" \
    '/^#/ { print; next } { $2=root "/" $1; print }' "${output}/graphs.tsv" > "${manifest_tmp}"
  awk -F '\t' -v OFS='\t' -v root="${output_root}" \
    'NR == 1 { print; next } { $21=root "/" $1; print }' \
    "${output}/fixture-provenance.tsv" > "${provenance_tmp}"
  mv "${manifest_tmp}" "${output}/graphs.tsv"
  mv "${provenance_tmp}" "${output}/fixture-provenance.tsv"
}

# actions/cache restores graph directories into a different runner workspace. Rebind only the
# location columns; all semantic and content hashes remain unchanged and are checked below.
relocate_output "${FIRST_OUTPUT}"
relocate_output "${SECOND_OUTPUT}"

diff -u \
  <(cut -f1-20 "${FIRST_OUTPUT}/fixture-provenance.tsv") \
  <(cut -f1-20 "${SECOND_OUTPUT}/fixture-provenance.tsv")
diff -u \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
    "${FIRST_OUTPUT}/graphs.tsv") \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
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
for OUTPUT in "${FIRST_OUTPUT}" "${SECOND_OUTPUT}"; do
  java -Xmx4g \
    -Dandroid.jar.path="${ANDROID_JAR}" \
    -Dtika.jar.path="${TIKA_JAR}" \
    -Dhive.jar.path="${HIVE_JAR}" \
    -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
    -cp "${JMH_JAR}" \
    io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
    --verify "${OUTPUT}/graphs.tsv" "${OUTPUT}/fixture-provenance.tsv"
done
java -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --self-test-order-fingerprint
TAMPERED_PROVENANCE=$(mktemp)
MODIFIED_FIXTURE_DIR=$(mktemp -d)
RESOURCE_BACKUP=
CALL_SITE_INDEX_BACKUP=
CALL_SITE_PREFILTER_BACKUP=
trap 'rm -f "${TAMPERED_PROVENANCE}"; rm -rf "${MODIFIED_FIXTURE_DIR}"; \
  [[ -z "${RESOURCE_BACKUP}" ]] || rm -f "${RESOURCE_BACKUP}"; \
  [[ -z "${CALL_SITE_INDEX_BACKUP}" ]] || rm -f "${CALL_SITE_INDEX_BACKUP}"; \
  [[ -z "${CALL_SITE_PREFILTER_BACKUP}" ]] || rm -f "${CALL_SITE_PREFILTER_BACKUP}"' EXIT
awk -F '\t' 'BEGIN { OFS="\t" } NR == 2 { $16=sprintf("%064d", 0) } { print }' \
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

RESOURCE_STORE=$(awk -F '\t' 'NR == 2 { print $21 "/graph.resources" }' \
  "${SECOND_OUTPUT}/fixture-provenance.tsv")
RESOURCE_BACKUP=$(mktemp)
cp "${RESOURCE_STORE}" "${RESOURCE_BACKUP}"
rm "${RESOURCE_STORE}"
if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${SECOND_OUTPUT}/graphs.tsv" "${SECOND_OUTPUT}/fixture-provenance.tsv"; then
  echo "Missing fixture64 graph.resources unexpectedly passed verification" >&2
  exit 1
fi
cp "${RESOURCE_BACKUP}" "${RESOURCE_STORE}"

CALL_SITE_INDEX=$(awk -F '\t' 'NR == 2 { print $21 "/graph.callsite-string-index" }' \
  "${SECOND_OUTPUT}/fixture-provenance.tsv")
CALL_SITE_INDEX_BACKUP=$(mktemp)
cp "${CALL_SITE_INDEX}" "${CALL_SITE_INDEX_BACKUP}"
truncate -s 3 "${CALL_SITE_INDEX}"
if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${SECOND_OUTPUT}/graphs.tsv" "${SECOND_OUTPUT}/fixture-provenance.tsv"; then
  echo "Corrupt fixture64 graph.callsite-string-index unexpectedly passed verification" >&2
  exit 1
fi
cp "${CALL_SITE_INDEX_BACKUP}" "${CALL_SITE_INDEX}"
CALL_SITE_PREFILTER=$(awk -F '\t' 'NR == 2 { print $21 "/graph.callsite-trigram-prefilter" }' \
  "${SECOND_OUTPUT}/fixture-provenance.tsv")
CALL_SITE_PREFILTER_BACKUP=$(mktemp)
cp "${CALL_SITE_PREFILTER}" "${CALL_SITE_PREFILTER_BACKUP}"
truncate -s 3 "${CALL_SITE_PREFILTER}"
if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${SECOND_OUTPUT}/graphs.tsv" "${SECOND_OUTPUT}/fixture-provenance.tsv"; then
  echo "Corrupt fixture64 graph.callsite-trigram-prefilter unexpectedly passed verification" >&2
  exit 1
fi
cp "${CALL_SITE_PREFILTER_BACKUP}" "${CALL_SITE_PREFILTER}"
truncate -s 3 "${RESOURCE_STORE}"
if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${SECOND_OUTPUT}/graphs.tsv" "${SECOND_OUTPUT}/fixture-provenance.tsv"; then
  echo "Corrupt fixture64 graph.resources unexpectedly passed verification" >&2
  exit 1
fi
cp "${RESOURCE_BACKUP}" "${RESOURCE_STORE}"
RESOURCE_SIZE=$(wc -c < "${RESOURCE_STORE}" | tr -d ' ')
LAST_RESOURCE_BYTE=$(tail -c 1 "${RESOURCE_STORE}" | od -An -tu1 | tr -d ' ')
if [[ "${LAST_RESOURCE_BYTE}" == 88 ]]; then
  REPLACEMENT_RESOURCE_BYTE=Y
else
  REPLACEMENT_RESOURCE_BYTE=X
fi
printf '%s' "${REPLACEMENT_RESOURCE_BYTE}" | \
  dd of="${RESOURCE_STORE}" bs=1 seek="$((RESOURCE_SIZE - 1))" conv=notrunc 2>/dev/null
if java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "${SECOND_OUTPUT}/graphs.tsv" "${SECOND_OUTPUT}/fixture-provenance.tsv"; then
  echo "Content-tampered fixture64 graph.resources unexpectedly passed verification" >&2
  exit 1
fi
cp "${RESOURCE_BACKUP}" "${RESOURCE_STORE}"

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

FIRST_PROVENANCE_SHA=$(cut -f1-20 "${FIRST_OUTPUT}/fixture-provenance.tsv" | sha256_stream)
SECOND_PROVENANCE_SHA=$(cut -f1-20 "${SECOND_OUTPUT}/fixture-provenance.tsv" | sha256_stream)
FIRST_MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
  "${FIRST_OUTPUT}/graphs.tsv" | sha256_stream)
SECOND_MANIFEST_SHA=$(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
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

echo "Fixture64 identities/resources/indexes are reproducible and all tampering is rejected"
