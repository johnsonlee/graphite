#!/usr/bin/env bash
set -euo pipefail
# Java is a build-time generator only. The server runs the generated Go code.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/../../.." && pwd)
version=4.13.2
expected_sha=eae2dfa119a64327444672aff63e9ec35a20180dc5b8090b7a6ab85125df4d76
antlr_jar=${ANTLR_JAR:-${TMPDIR:-/tmp}/graphite-antlr-${version}-complete.jar}
if [[ ! -f "$antlr_jar" ]]; then
  curl --fail --location --silent --show-error "https://www.antlr.org/download/antlr-${version}-complete.jar" --output "$antlr_jar"
fi
if command -v sha256sum >/dev/null 2>&1; then
  actual_sha=$(sha256sum "$antlr_jar" | cut -d ' ' -f 1)
else
  actual_sha=$(shasum -a 256 "$antlr_jar" | cut -d ' ' -f 1)
fi
if [[ "$actual_sha" != "$expected_sha" ]]; then
  echo "ANTLR generator checksum mismatch: $antlr_jar" >&2
  exit 1
fi
cd "$repo_dir"
java -jar "$antlr_jar" -Dlanguage=Go -package generated -visitor -no-listener \
  -Xexact-output-dir -o graphite-server/internal/cypher/generated \
  graphite-cypher/src/main/antlr/CypherLexer.g4 \
  graphite-cypher/src/main/antlr/CypherParser.g4
cd "$repo_dir/graphite-server"
go run ./internal/cypher/cmd/normalize ./internal/cypher/generated/cypher_parser.go
gofmt -w internal/cypher/generated/*.go
