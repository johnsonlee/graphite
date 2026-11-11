#!/bin/bash
# usage: start-server.sh <binary> <manifest> <port> [extra args]
BIN=$1; MANIFEST=$2; PORT=$3; shift 3
ARGS=()
while IFS=$'\t' read -r id path rest; do
  case "$id" in \#*|"") continue;; esac
  ARGS+=(--graph "$id:$path")
done < "$MANIFEST"
exec "$BIN" serve "${ARGS[@]}" --port "$PORT" --metrics "$@"
