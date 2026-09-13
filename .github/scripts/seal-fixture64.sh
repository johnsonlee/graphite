#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 7 ]]; then
  echo 'Usage: seal-fixture64.sh <hit|miss> <jmh.jar> <fixtures> <output> <repeat> <input-key> <candidate-sha>' >&2
  exit 2
fi
MODE=$1
CANDIDATE_JAR=$2
FIXTURE_DIR=$3
OUTPUT=$4
REPEAT=$5
FIXTURE64_INPUT_KEY=$6
CANDIDATE_SHA=$7
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
[[ "$MODE" == hit || "$MODE" == miss ]]
[[ "$CANDIDATE_SHA" =~ ^[0-9a-f]{40}$ ]]
test -n "$FIXTURE64_INPUT_KEY"
test -f "$CANDIDATE_JAR"
test -f "$OUTPUT/graphs/graphs.tsv"
test -f "$OUTPUT/graphs/fixture-provenance.tsv"
if [[ "$MODE" == hit ]]; then
  "$SCRIPT_DIR/reuse-fixture64-receipt.sh" "$CANDIDATE_JAR" "$FIXTURE_DIR" \
    "$OUTPUT/graphs" "$OUTPUT/fixture-reproducibility.json" "$FIXTURE64_INPUT_KEY"
else
  # Aggregate must never regenerate a missing second producer's evidence.
  test -f "$REPEAT/graphs.tsv"
  test -f "$REPEAT/fixture-provenance.tsv"
  "$SCRIPT_DIR/test-fixture64-reproducibility.sh" "$CANDIDATE_JAR" "$FIXTURE_DIR" \
    "$OUTPUT/graphs" "$REPEAT" "$OUTPUT/fixture-reproducibility.json"
  jq --arg inputKey "$FIXTURE64_INPUT_KEY" '. + {inputKey: $inputKey}' \
    "$OUTPUT/fixture-reproducibility.json" > "$OUTPUT/receipt.tmp"
  mv "$OUTPUT/receipt.tmp" "$OUTPUT/fixture-reproducibility.json"
fi
FIXTURE_JARS=()
while IFS= read -r fixture; do FIXTURE_JARS+=("$fixture"); done < <(find "${FIXTURE_DIR}" -maxdepth 1 -type f -name '*.jar' -print | sort)
test "${#FIXTURE_JARS[@]}" -eq 4
FIXTURE_JAR_SET_SHA=$(
  for FIXTURE_JAR in "${FIXTURE_JARS[@]}"; do
    printf '%s\t%s\n' "$(basename "${FIXTURE_JAR}")" \
      "$(sha256sum "${FIXTURE_JAR}" | awk '{print $1}')"
  done | sha256sum | awk '{print $1}'
)
jq -n \
  --arg schema graphite-shared-fixture64-v1 \
  --arg candidateSha "${CANDIDATE_SHA}" \
  --arg fixtureJarSetSha256 "${FIXTURE_JAR_SET_SHA}" \
  --arg manifestSha256 "$(sha256sum "${OUTPUT}/graphs/graphs.tsv" | awk '{print $1}')" \
  --arg provenanceSha256 \
    "$(sha256sum "${OUTPUT}/graphs/fixture-provenance.tsv" | awk '{print $1}')" \
  --arg receiptSha256 \
    "$(sha256sum "${OUTPUT}/fixture-reproducibility.json" | awk '{print $1}')" \
  '{schema: $schema, complete: true, candidateSha: $candidateSha,
    fixtureJarSetSha256: $fixtureJarSetSha256, manifestSha256: $manifestSha256,
    provenanceSha256: $provenanceSha256, receiptSha256: $receiptSha256}' \
  > "${OUTPUT}/fixture64.complete.json"
