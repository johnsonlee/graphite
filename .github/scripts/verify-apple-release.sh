#!/usr/bin/env bash
# Exercise the installed release through the public CLI, including a fresh Swift build,
# IR import, persisted graph verification, and queries with known semantic results.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo 'Usage: verify-apple-release.sh <graphite> <AcmeShop fixture> <output directory>' >&2
  exit 2
fi
GRAPHITE=$1
FIXTURE=$2
OUTPUT=$3
mkdir -p "$OUTPUT"
# Never borrow a source checkout's existing index store: the shipped frontend must
# successfully drive the toolchain and discover the package's nonstandard target.
cp -R "$FIXTURE" "$OUTPUT/AcmeShop"
rm -rf "$OUTPUT/AcmeShop/.build" "$OUTPUT/AcmeShop/.swiftpm"
GRAPH="$OUTPUT/acme.graphite"
"$GRAPHITE" frontend describe apple
"$GRAPHITE" build "$OUTPUT/AcmeShop" -o "$GRAPH"
"$GRAPHITE" verify "$GRAPH"
"$GRAPHITE" query "$GRAPH" "MATCH (m:Method) WHERE m.class = 'AcmeShop.CartService' RETURN m.name ORDER BY m.name" -f csv > "$OUTPUT/methods.csv"
grep -Fx '"checkout(order:method:)"' "$OUTPUT/methods.csv"
grep -Fx '"init(client:)"' "$OUTPUT/methods.csv"
"$GRAPHITE" query "$GRAPH" "MATCH (s:Constant)-[:DATAFLOW]->(c:CallSite) WHERE c.callee_name = 'isEnabled(_:default:)' RETURN s.value ORDER BY s.value" -f csv > "$OUTPUT/constants.csv"
grep -Fx '"payments.charge"' "$OUTPUT/constants.csv"
grep -Fx 'true' "$OUTPUT/constants.csv"
"$GRAPHITE" query "$GRAPH" "MATCH (c:CallSite) WHERE c.caller_class = 'AcmeShopTool.Tool' RETURN c.callee_name ORDER BY c.callee_name" -f csv > "$OUTPUT/tool-calls.csv"
grep -Fx '"checkout(order:method:)"' "$OUTPUT/tool-calls.csv"
