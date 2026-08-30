#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=${1:?usage: verify-jmh-jar-isolation.sh <project-dir> <jmh-jar>}
JMH_JAR=${2:?usage: verify-jmh-jar-isolation.sh <project-dir> <jmh-jar>}
test -d "${PROJECT_DIR}"
test -f "${JMH_JAR}"

WORK_DIR=$(mktemp -d)
trap 'rm -rf -- "${WORK_DIR}"' EXIT
: > "${WORK_DIR}/test-entries.txt"
for ROOT in \
    "${PROJECT_DIR}/build/classes/java/test" \
    "${PROJECT_DIR}/build/classes/kotlin/test" \
    "${PROJECT_DIR}/build/resources/test"; do
    if [[ -d "${ROOT}" ]]; then
        (
            cd "${ROOT}"
            find . -type f -print | sed 's#^\./##'
        ) >> "${WORK_DIR}/test-entries.txt"
    fi
done
sort -u -o "${WORK_DIR}/test-entries.txt" "${WORK_DIR}/test-entries.txt"
test -s "${WORK_DIR}/test-entries.txt"
jar tf "${JMH_JAR}" | sort -u > "${WORK_DIR}/jar-entries.txt"
comm -12 "${WORK_DIR}/test-entries.txt" "${WORK_DIR}/jar-entries.txt" > "${WORK_DIR}/leaks.txt"
if [[ -s "${WORK_DIR}/leaks.txt" ]]; then
    echo "JMH JAR contains project test output:" >&2
    cat "${WORK_DIR}/leaks.txt" >&2
    exit 1
fi
