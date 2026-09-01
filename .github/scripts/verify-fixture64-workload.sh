#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <evidence-fixture-directory> <recomputed-fixture-directory>" >&2
  exit 2
fi

EVIDENCE_DIR=$1
RECOMPUTED_DIR=$2

diff -u \
  <(cut -f1-13 "${EVIDENCE_DIR}/fixture-provenance.tsv") \
  <(cut -f1-13 "${RECOMPUTED_DIR}/fixture-provenance.tsv")
diff -u \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
    "${EVIDENCE_DIR}/graphs.tsv") \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5 }' \
    "${RECOMPUTED_DIR}/graphs.tsv")

echo "Fixture64 evidence workload matches the independently regenerated corpus"
