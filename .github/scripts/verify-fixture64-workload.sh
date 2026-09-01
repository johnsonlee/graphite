#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 13 ]]; then
  echo "Usage: $0 <evidence-fixture-directory> <recomputed-fixture-directory>" \
    "<reference-observations> <reference-correctness> <semantic-oracle>" \
    "<base-cold-observations> <base-cold-correctness>" \
    "<candidate-cold-observations> <candidate-cold-correctness>" \
    "<base-warm-observations> <base-warm-correctness>" \
    "<candidate-warm-observations> <candidate-warm-correctness>" >&2
  exit 2
fi

EVIDENCE_DIR=$1
RECOMPUTED_DIR=$2
REFERENCE_OBSERVATIONS=$3
REFERENCE_CORRECTNESS=$4
SEMANTIC_ORACLE=$5
BASE_COLD_OBSERVATIONS=$6
BASE_COLD_CORRECTNESS=$7
CANDIDATE_COLD_OBSERVATIONS=$8
CANDIDATE_COLD_CORRECTNESS=$9
BASE_WARM_OBSERVATIONS=${10}
BASE_WARM_CORRECTNESS=${11}
CANDIDATE_WARM_OBSERVATIONS=${12}
CANDIDATE_WARM_CORRECTNESS=${13}

diff -u \
  <(cut -f1-16 "${EVIDENCE_DIR}/fixture-provenance.tsv") \
  <(cut -f1-16 "${RECOMPUTED_DIR}/fixture-provenance.tsv")
diff -u \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
    "${EVIDENCE_DIR}/graphs.tsv") \
  <(awk -F '\t' 'BEGIN { OFS="\t" } /^#/ { print; next } { print $1, $3, $4, $5, $6 }' \
    "${RECOMPUTED_DIR}/graphs.tsv")

echo "Fixture64 evidence workload matches the independently regenerated corpus"

OBSERVATIONS=(
  "${REFERENCE_OBSERVATIONS}"
  "${BASE_COLD_OBSERVATIONS}"
  "${CANDIDATE_COLD_OBSERVATIONS}"
  "${BASE_WARM_OBSERVATIONS}"
  "${CANDIDATE_WARM_OBSERVATIONS}"
)
CORRECTNESS_RECORDS=(
  "${REFERENCE_CORRECTNESS}"
  "${SEMANTIC_ORACLE}"
  "${BASE_COLD_CORRECTNESS}"
  "${CANDIDATE_COLD_CORRECTNESS}"
  "${BASE_WARM_CORRECTNESS}"
  "${CANDIDATE_WARM_CORRECTNESS}"
)

for RESULT in "${OBSERVATIONS[@]}"; do
  test -f "${RESULT}"
  awk -F '\t' '
    NR == FNR {
      if (FNR > 1) {
        ordinal = FNR - 2
        targetOrdinal[$1] = ordinal
        expectedIdentity[$1] = $16
      }
      next
    }
    FNR == 1 {
      for (column = 1; column <= NF; column++) columns[$column] = column
      required = "id family shape selectivity targetGraphId workloadIdentity outcome rowCount responseBytes digest latencyNanos"
      split(required, names, " ")
      for (nameIndex in names) if (!columns[names[nameIndex]]) exit 1
      next
    }
    {
      target = $columns["targetGraphId"]
      ordinal = targetOrdinal[target]
      canonicalId = $columns["shape"] "-target-" sprintf("%02d", ordinal) "-" $columns["selectivity"]
      if (!(target in targetOrdinal) || $columns["id"] != canonicalId) exit 1
      if ($columns["workloadIdentity"] != expectedIdentity[target]) exit 1
      if (seen[$columns["id"]]++) exit 1
      counts[target]++
      rows++
    }
    END {
      if (rows != 192 && rows != 768) exit 1
      expectedPerTarget = rows / 64
      for (target in expectedIdentity) if (counts[target] != expectedPerTarget) exit 1
    }
  ' "${RECOMPUTED_DIR}/fixture-provenance.tsv" "${RESULT}"
done

for RESULT in "${CORRECTNESS_RECORDS[@]}"; do
  test -f "${RESULT}"
  awk -F '|' '
    NR == FNR {
      if (FNR > 1) {
        split($0, columns, "\t")
        ordinal = FNR - 2
        targetOrdinal[columns[1]] = ordinal
        expectedIdentity[columns[1]] = columns[16]
      }
      next
    }
    {
      if (NF != 14) exit 1
      target = $8
      ordinal = targetOrdinal[target]
      canonicalId = $3 "-target-" sprintf("%02d", ordinal) "-" $4
      if (!(target in targetOrdinal) || $1 != canonicalId) exit 1
      if ($9 != expectedIdentity[target]) exit 1
      if (seen[$1]++) exit 1
      counts[target]++
      rows++
    }
    END {
      if (rows != 192 && rows != 768) exit 1
      expectedPerTarget = rows / 64
      for (target in expectedIdentity) if (counts[target] != expectedPerTarget) exit 1
    }
  ' "${RECOMPUTED_DIR}/fixture-provenance.tsv" "${RESULT}"
done

normalize_observations() {
  awk -F '\t' 'BEGIN { OFS="|" } NR > 1 {
    print $1, $2, $3, $4, $5, $6, $7, $9, $10, $8, $11, $12, $13, $14
  }' "$1" | sort
}

normalize_correctness() {
  sort "$1"
}

compare_records() {
  local observations=$1
  local correctness=$2
  diff -u <(normalize_correctness "${correctness}") <(normalize_observations "${observations}")
}

# Every latency-bearing observation must reproduce a separately emitted correctness record.
compare_records "${REFERENCE_OBSERVATIONS}" "${REFERENCE_CORRECTNESS}"
compare_records "${BASE_COLD_OBSERVATIONS}" "${BASE_COLD_CORRECTNESS}"
compare_records "${CANDIDATE_COLD_OBSERVATIONS}" "${CANDIDATE_COLD_CORRECTNESS}"
compare_records "${BASE_WARM_OBSERVATIONS}" "${BASE_WARM_CORRECTNESS}"
compare_records "${CANDIDATE_WARM_OBSERVATIONS}" "${CANDIDATE_WARM_CORRECTNESS}"

# Main's cold and warm records are independent executions and must describe identical behavior.
diff -u \
  <(normalize_correctness "${BASE_COLD_CORRECTNESS}") \
  <(normalize_correctness "${BASE_WARM_CORRECTNESS}")

# Candidate correctness is a hard gate against the semantic oracle independently derived from
# main's correct one-graph-at-a-time /api/cypher/graphs path.
for RESULT in "${CANDIDATE_COLD_CORRECTNESS}" "${CANDIDATE_WARM_CORRECTNESS}"; do
  diff -u \
    <(normalize_correctness "${SEMANTIC_ORACLE}") \
    <(normalize_correctness "${RESULT}")
done

echo "Every fixture64 latency row has a canonical ID and a matching independent correctness record"
