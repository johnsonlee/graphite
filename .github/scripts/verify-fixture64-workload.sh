#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <evidence-fixture-directory> <recomputed-fixture-directory> <result>..." >&2
  exit 2
fi

EVIDENCE_DIR=$1
RECOMPUTED_DIR=$2

diff -u \
  <(cut -f1-16 "${EVIDENCE_DIR}/fixture-provenance.tsv") \
  <(cut -f1-16 "${RECOMPUTED_DIR}/fixture-provenance.tsv")
diff -u \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
    "${EVIDENCE_DIR}/graphs.tsv") \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
    "${RECOMPUTED_DIR}/graphs.tsv")

echo "Fixture64 evidence workload matches the independently regenerated corpus"

shift 2
for RESULT in "$@"; do
  test -f "${RESULT}"
  FIRST_LINE=$(head -n 1 "${RESULT}")
  if [[ "${FIRST_LINE}" == *$'\ttargetGraphId\tworkloadIdentity\t'* ]]; then
    awk -F '\t' '
      NR == FNR {
        if (FNR > 1) {
          ordinal = FNR - 2
          expectedTarget[ordinal] = $1
          expectedIdentity[$1] = $16
        }
        next
      }
      FNR == 1 {
        for (column = 1; column <= NF; column++) {
          if ($column == "id") idColumn = column
          if ($column == "targetGraphId") targetColumn = column
          if ($column == "workloadIdentity") identityColumn = column
        }
        if (!idColumn || !targetColumn || !identityColumn) exit 1
        next
      }
      {
        split($idColumn, idParts, "-target-")
        ordinalText = substr(idParts[2], 1, 2)
        target = $targetColumn
        identity = $identityColumn
        if (length(idParts) != 2 || ordinalText !~ /^[0-9][0-9]$/) exit 1
        ordinal = ordinalText + 0
        if (target != expectedTarget[ordinal] || identity != expectedIdentity[target]) exit 1
        counts[target]++
        rows++
      }
      END {
        if (rows != 192 && rows != 768) exit 1
        expectedPerTarget = rows / 64
        for (target in expectedIdentity) if (counts[target] != expectedPerTarget) exit 1
      }
    ' "${RECOMPUTED_DIR}/fixture-provenance.tsv" "${RESULT}"
  else
    awk -F '|' '
      NR == FNR {
        if (FNR > 1) {
          split($0, columns, "\t")
          ordinal = FNR - 2
          expectedTarget[ordinal] = columns[1]
          expectedIdentity[columns[1]] = columns[16]
        }
        next
      }
      {
        split($1, idParts, "-target-")
        ordinalText = substr(idParts[2], 1, 2)
        target = $8
        identity = $9
        if (length(idParts) != 2 || ordinalText !~ /^[0-9][0-9]$/) exit 1
        ordinal = ordinalText + 0
        if (target != expectedTarget[ordinal] || identity != expectedIdentity[target]) exit 1
        counts[target]++
        rows++
      }
      END {
        if (rows != 192 && rows != 768) exit 1
        expectedPerTarget = rows / 64
        for (target in expectedIdentity) if (counts[target] != expectedPerTarget) exit 1
      }
    ' "${RECOMPUTED_DIR}/fixture-provenance.tsv" "${RESULT}"
  fi
done

echo "Every fixture64 runtime result is bound to the regenerated target/workload mapping"
