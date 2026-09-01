#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <webgraph-jmh.jar> <fixture-jar-directory> <first-output> <second-output>" >&2
  exit 2
fi

JMH_JAR=$1
FIXTURE_DIR=$2
FIRST_OUTPUT=$3
SECOND_OUTPUT=$4
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

test -f "${JMH_JAR}"
test -d "${FIXTURE_DIR}"
test ! -e "${FIRST_OUTPUT}"
test ! -e "${SECOND_OUTPUT}"

"${SCRIPT_DIR}/prepare-fixture64-graphs.sh" "${JMH_JAR}" "${FIXTURE_DIR}" "${FIRST_OUTPUT}"
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
TAMPERED_PROVENANCE=$(mktemp)
trap 'rm -f "${TAMPERED_PROVENANCE}"' EXIT
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

echo "Fixture64 identities are reproducible and tampered provenance is rejected"
