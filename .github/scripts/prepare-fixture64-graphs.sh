#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <webgraph-jmh.jar> <fixture-jar-directory> <output-directory>" >&2
  exit 2
fi

JMH_JAR=$1
FIXTURE_DIR=$2
OUTPUT_DIR=$3

test -f "${JMH_JAR}"
test -d "${FIXTURE_DIR}"
test ! -e "${OUTPUT_DIR}"

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

java -Xmx4g \
  -Dandroid.jar.path="${ANDROID_JAR}" \
  -Dtika.jar.path="${TIKA_JAR}" \
  -Dhive.jar.path="${HIVE_JAR}" \
  -Dkotlin.compiler.jar.path="${KOTLIN_JAR}" \
  -cp "${JMH_JAR}" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  "${OUTPUT_DIR}"

test -f "${OUTPUT_DIR}/graphs.tsv"
test -f "${OUTPUT_DIR}/fixture-provenance.tsv"
test "$(grep -cv '^#' "${OUTPUT_DIR}/graphs.tsv")" -eq 64
test "$(tail -n +2 "${OUTPUT_DIR}/fixture-provenance.tsv" | wc -l | tr -d ' ')" -eq 64
test "$(cut -f2 "${OUTPUT_DIR}/fixture-provenance.tsv" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 4
test "$(cut -f16 "${OUTPUT_DIR}/fixture-provenance.tsv" | tail -n +2 | sort -u | wc -l | tr -d ' ')" -eq 64

echo "Prepared 64 distinct fixture-derived graphs in ${OUTPUT_DIR}"
