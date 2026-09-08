The functional quote correction passes the full isolated Go module race suite and vet,
using Go 1.22.0 darwin/arm64. `receipt.json` records both successful terminal commands.
The JSON test stream explicitly records all 686 original-Java quote subcases passing:
609 primary cases plus 77 quote-index controls. No performance benchmark ran.

`module-source.json` identifies every file in the isolated module. It is the complete
previous frozen AST-cache module with only the staged `regex.go`, `quote.go`, and
`quote_test.go` overlaid. It has no quoted-contains plan or fast-path tests. The three
archived `.go.txt` files preserve exactly the staged bytes tested. The OpenAPI test's
three original Kotlin source siblings were separately copied and checked against its
source manifest. All 16,498 recorded inputs (module, external fixtures, Go toolchain,
module dependencies, runner, and overlays) were unchanged after both commands;
`inputs-before.json.gz` and `inputs-after.json.gz` are equal after decompression.

The first run is retained in `failed-setup/`: all 686 quote subcases passed, but the
OpenAPI drift test failed because the isolated directory omitted its Kotlin source
siblings. The second run corrected that setup and reran the full suite. No production
code was changed to address this harness error.

The full test JSON streams are losslessly compressed as `race.jsonl.gz` and
`failed-setup/race.jsonl.gz`. `check-log-archives.json` records compressed and
uncompressed hashes and the retained external raw paths. `receipt.json` preserves
the original command/log names; decompress the corresponding archive to read them.

Reproduce the functional-only checks after commit with:

```sh
python3 docs/go-server-baseline/native-regex-quoted-contains/quote-functional/run-checks.py \
  --from-archive --directory /absolute/new/external/check-directory
```

This uses the archived overlay rather than the subsequently changing Git index and
writes fresh receipts under the new directory's `checks/` folder. It requires the
frozen baseline module and the original oracle fixtures at the recorded paths.
The companion real64 capture has its own `real64-module-source.json`; it is separate
from this full module test snapshot.
