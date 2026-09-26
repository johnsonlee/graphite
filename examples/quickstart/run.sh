#!/usr/bin/env bash
set -euo pipefail

# GRAPHITE_BIN can select a specific installation when several are on PATH.
graphite_bin="${GRAPHITE_BIN:-graphite}"
example_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
demo_dir="$(mktemp -d "${TMPDIR:-/tmp}/graphite-quickstart.XXXXXX")"
printf 'Demo directory: %s\n' "$demo_dir"

javac --release 17 -d "$demo_dir/classes" "$example_dir/Checkout.java"
jar --create --file "$demo_dir/checkout.jar" -C "$demo_dir/classes" .
"$graphite_bin" build "$demo_dir/checkout.jar" -o "$demo_dir/checkout-graph" --include demo

printf '\nWhich constant reaches enableFeature?\n'
"$graphite_bin" query --format json "$demo_dir/checkout-graph" \
  "MATCH (c:IntConstant)-[:DATAFLOW*]->(call:CallSiteNode)
   WHERE call.callee_class = 'demo.Checkout' AND call.callee_name = 'enableFeature'
   RETURN DISTINCT c.value AS flag, call.callee_name AS method"

printf '\nWho calls enableFeature?\n'
"$graphite_bin" query --format json "$demo_dir/checkout-graph" \
  "MATCH (call:CallSiteNode)
   WHERE call.callee_class = 'demo.Checkout' AND call.callee_name = 'enableFeature'
   RETURN call.caller_signature AS caller"

printf '\nExplore the saved graph:\n  %q serve --id demo %q --port 8080\n' \
  "$graphite_bin" "$demo_dir/checkout-graph"
printf '\nDemo files are kept in %s for inspection.\n' "$demo_dir"
