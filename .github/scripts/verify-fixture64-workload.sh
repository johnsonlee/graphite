#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 13 && $# -ne 17 ]]; then
  echo "Usage: $0 <evidence-fixture-directory> <recomputed-fixture-directory>" \
    "<reference-observations> <reference-correctness> <semantic-oracle>" \
    "<base-cold-observations> <base-cold-correctness>" \
    "<candidate-cold-observations> <candidate-cold-correctness>" \
    "<base-warm-observations> <base-warm-correctness>" \
    "<candidate-warm-observations> <candidate-warm-correctness>" \
    "[<base-startup-prepared-observations> <base-startup-prepared-correctness>" \
    "<candidate-startup-prepared-observations> <candidate-startup-prepared-correctness>]" >&2
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
  <(cut -f1-20 "${EVIDENCE_DIR}/fixture-provenance.tsv") \
  <(cut -f1-20 "${RECOMPUTED_DIR}/fixture-provenance.tsv")
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
if [[ $# -eq 17 ]]; then
  OBSERVATIONS+=("${14}" "${16}")
  CORRECTNESS_RECORDS+=("${15}" "${17}")
fi

for RESULT_INDEX in "${!OBSERVATIONS[@]}"; do
  RESULT=${OBSERVATIONS[${RESULT_INDEX}]}
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
      enhanced = columns["targetGraphIds"] && columns["selectedGraphCount"]
      if (enhanced && (!columns["accessedGraphCount"] || !columns["accessedGraphIds"] ||
          !columns["targetGraphAccessCount"] || !columns["nonTargetGraphAccessCount"])) exit 1
      next
    }
    {
      target = $columns["targetGraphId"]
      if (!(target in targetOrdinal)) exit 1
      ordinal = targetOrdinal[target]
      family = $columns["family"]
      if (!enhanced) {
        canonicalId = $columns["shape"] "-target-" sprintf("%02d", ordinal) "-" $columns["selectivity"]
        if ($columns["id"] != canonicalId || $columns["workloadIdentity"] != expectedIdentity[target]) exit 1
        singleRows++
        if (seen[$columns["id"]]++) exit 1
        rows++
        next
      }
      width = $columns["selectedGraphCount"] + 0
      if (family == "graph-id" || family == "graph-parameter") {
        canonicalId = $columns["shape"] "-target-" sprintf("%02d", ordinal) "-" $columns["selectivity"]
        if ($columns["id"] != canonicalId || width != 1 || $columns["targetGraphIds"] != target) exit 1
        if ($columns["workloadIdentity"] != expectedIdentity[target]) exit 1
        if ($columns["accessedGraphCount"] != 1 || $columns["accessedGraphIds"] != target ||
            $columns["nonTargetGraphAccessCount"] != 0) exit 1
        singleRows++
      } else if (family == "graph-id-set" || family == "graph-set-reference") {
        if (width != 2 && width != 8 && width != 64) exit 1
        if (ordinal % width != 0) exit 1
        group = ordinal / width
        canonicalId = $columns["shape"] "-k" sprintf("%02d", width) "-group-" \
          sprintf("%02d", group) "-" $columns["selectivity"]
        if ($columns["id"] != canonicalId) exit 1
        count = split($columns["targetGraphIds"], graphIds, ",")
        if (count != width) exit 1
        delete selected
        for (i = 1; i <= count; i++) {
          if (!(graphIds[i] in targetOrdinal) || targetOrdinal[graphIds[i]] != ordinal + i - 1) exit 1
          selected[graphIds[i]] = 1
        }
        expectedAccesses = $columns["selectivity"] == "dense" ? 1 : width
        expectedTarget = $columns["selectivity"] == "dense" ? 1 : width
        accessCount = split($columns["accessedGraphIds"], accessedIds, ",")
        if ($columns["accessedGraphCount"] != expectedAccesses || accessCount != expectedAccesses ||
            $columns["targetGraphAccessCount"] != expectedTarget ||
            $columns["nonTargetGraphAccessCount"] != 0) exit 1
        delete accessed
        for (i = 1; i <= accessCount; i++) {
          if (!(accessedIds[i] in targetOrdinal) || accessed[accessedIds[i]]++) exit 1
          if (!(accessedIds[i] in selected)) exit 1
        }
        if ($columns["workloadIdentity"] !~ /^[0-9a-f]{64}$/) exit 1
        setRows++
      } else exit 1
      if (seen[$columns["id"]]++) exit 1
      rows++
    }
    END {
      if (!((rows == 192 && singleRows == 192 && setRows == 0) ||
            (rows == 768 && singleRows == 768 && setRows == 0) ||
            (rows == 315 && singleRows == 192 && setRows == 123) ||
            (rows == 1137 && singleRows == 768 && setRows == 369))) exit 1
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
      if (!(target in targetOrdinal)) exit 1
      ordinal = targetOrdinal[target]
      if ($2 == "graph-id" || $2 == "graph-parameter") {
        canonicalId = $3 "-target-" sprintf("%02d", ordinal) "-" $4
        if ($1 != canonicalId || $9 != expectedIdentity[target]) exit 1
        singleRows++
      } else if ($2 == "graph-id-set" || $2 == "graph-set-reference") {
        if (match($1, /-k(02|08|64)-group-[0-9][0-9]-(zero|targeted|dense)$/) == 0) exit 1
        if ($9 !~ /^[0-9a-f]{64}$/) exit 1
        setRows++
      } else exit 1
      if (seen[$1]++) exit 1
      rows++
    }
    END {
      if (!((rows == 192 && singleRows == 192 && setRows == 0) ||
            (rows == 768 && singleRows == 768 && setRows == 0) ||
            (rows == 315 && singleRows == 192 && setRows == 123) ||
            (rows == 1137 && singleRows == 768 && setRows == 369))) exit 1
    }
  ' "${RECOMPUTED_DIR}/fixture-provenance.tsv" "${RESULT}"
done

normalize_observations() {
  awk -F '\t' 'BEGIN { OFS="|" } NR == 1 {
    for (i = 1; i <= NF; i++) column[$i] = i
    next
  } {
    print $column["id"], $column["family"], $column["shape"], $column["selectivity"],
      $column["operator"], $column["boundary"], $column["projection"], $column["targetGraphId"],
      $column["workloadIdentity"], $column["limit"], $column["outcome"], $column["rowCount"],
      $column["responseBytes"], $column["digest"]
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

if [[ $# -eq 17 ]]; then
  diff -u \
    <(normalize_correctness "${BASE_COLD_CORRECTNESS}") \
    <(normalize_correctness "${15}")
  diff -u \
    <(normalize_correctness "${SEMANTIC_ORACLE}") \
    <(normalize_correctness "${17}")
fi

echo "Every fixture64 latency row has a canonical ID and a matching independent correctness record"
