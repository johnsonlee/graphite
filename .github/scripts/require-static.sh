#!/usr/bin/env bash
# Fail unless a Linux binary is statically linked: the release tarballs must run on any
# Linux distribution and the image is distroless `static` (no libc at all), so a binary
# that still needs a dynamic loader or any GLIBC symbol must not be packaged.
set -euo pipefail
test "$#" -eq 1 || { echo 'Usage: require-static.sh <binary>' >&2; exit 2; }
BINARY=$1
DESC=$(file -b "$BINARY")
echo "${BINARY}: ${DESC}"
case "$DESC" in
  *"statically linked"*|*"static-pie linked"*) ;;
  *) echo "${BINARY} is not statically linked" >&2; exit 1 ;;
esac
if objdump -T "$BINARY" 2>/dev/null | grep -q 'GLIBC_'; then
  echo "${BINARY} references GLIBC symbols" >&2; exit 1
fi
