#!/usr/bin/env bash
# Print the highest GLIBC symbol version a Linux binary requires, and fail when it is
# above the ceiling given as the second argument. The release image is distroless
# cc-debian12 (glibc 2.36) and the tarballs are meant to run on any current
# distribution, so the binaries must be built against a glibc no newer than that.
set -euo pipefail
test "$#" -eq 2 || { echo 'Usage: max-glibc.sh <binary> <max-version>' >&2; exit 2; }
BINARY=$1; MAX=$2
NEED=$(objdump -T "$BINARY" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -uV | tail -1)
echo "${BINARY}: needs glibc ${NEED} (ceiling ${MAX})"
if [[ "$(printf '%s\n%s\n' "$NEED" "$MAX" | sort -V | tail -1)" != "$MAX" ]]; then
  echo "glibc ${NEED} is newer than the ${MAX} ceiling; build on an older runner" >&2
  exit 1
fi
