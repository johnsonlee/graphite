#!/bin/sh
# Run from the isolated fixture directory on the release target and its native libc.
set -eu
version=$(env -i PATH='' HOME="$PWD" ./frontend --version)
test "$version" = "graphite $1"
env -i PATH='' HOME="$PWD" ./frontend --licenses > licenses.txt
grep -Fq 'TypeScript third-party notices' licenses.txt
env -i PATH='' HOME="$PWD" ./frontend build app.mjs -o actual.json
cmp expected.json actual.json
