#!/usr/bin/env bash
# Runs `swift <args>` for this package with the flags the toolchain needs.
#
# On Linux, indexstore-db's C++ sources include <dispatch/dispatch.h> and <Block.h>, which
# live in the toolchain under usr/lib/swift and are not on clang's default search path
# (the same flags sourcekit-lsp documents). On macOS the toolchain finds them itself.
#
#   frontend/apple/swift.sh build -c release
#   frontend/apple/swift.sh test
set -euo pipefail
cd "$(dirname "$0")"

SWIFT="${GRAPHITE_SWIFT:-$(command -v swift)}"
flags=()
if [[ "$(uname -s)" == "Linux" ]]; then
  toolchain="$(cd "$(dirname "$(readlink -f "$SWIFT")")/../.." && pwd)"
  flags+=(-Xcxx "-I$toolchain/usr/lib/swift" -Xcxx "-I$toolchain/usr/lib/swift/Block")
fi
exec "$SWIFT" "$@" "${flags[@]}"
