# Raw JSON compatibility against pinned main

Captured 288 raw-body requests against main `4e328b0109e13c896b74004823fb049fcb19251a`, using Java17.0.18, Gson2.11.0, and one tiny persisted fixture. The dedicated JVM was stopped after capture. This is correctness evidence only. Requests and responses are preserved as base64, with original headers and full server logs.

The native replay now passes **288/288**, and the existing valid-JSON oracle still passes **213/213**. The separate direct Gson oracle compares typed JSON trees, UTF-16 string units, insertion order, and exact error messages/positions; the separate HTTP non-finite suite passes **30/30**. No performance or complete Gson-equivalence claim follows from these finite cases.

All requests here include URL `query=RETURN 2 AS x&graph=a`. A valid body query overrides it on root/explicit GET and POST and scoped POST; scoped GET uses the URL query. Malformed-body behavior without a usable URL query can stop earlier at the missing-query check and is not represented by this matrix.

Root/scoped malformed bodies reach timeout parsing after the usable URL query has been selected; Gson parse/type failures escape that stage as Javalin500 problem JSON. Explicit graph-selection routes parse the body before admission and report those same failures as400 with the exact Gson error. Native code preserves these phases instead of mapping all parser errors to one status.

Gson parses one lenient value, restores legacy strictness, then checks for trailing content. Thus leading comments, single quotes, unquoted keys, `=`/`=>`, semicolon separators, BOM, and the nonexecute prefix can be accepted while trailing comments or another top-level value fail. Empty/trailing array elements become JsonNull; object trailing commas remain malformed. The tokenizer tracks UTF-16 cursor positions and path state directly.

| Payload | Root GET/POST | Scoped GET/POST | Explicit GET/POST |
|---|---:|---:|---:|
| valid | 200 / 200 | 200 / 200 | 200 / 200 |
| empty | 200 / 200 | 200 / 200 | 200 / 200 |
| whitespace | 200 / 200 | 200 / 200 | 200 / 200 |
| nul | 500 / 500 | 500 / 500 | 400 / 400 |
| empty-object | 200 / 200 | 200 / 200 | 400 / 400 |
| null | 500 / 500 | 500 / 500 | 400 / 400 |
| array | 500 / 500 | 500 / 500 | 400 / 400 |
| top-string | 500 / 500 | 500 / 500 | 400 / 400 |
| line-comment | 200 / 200 | 200 / 200 | 200 / 200 |
| block-comment | 500 / 500 | 500 / 500 | 400 / 400 |
| hash-comment | 200 / 200 | 200 / 200 | 200 / 200 |
| comment-inside | 200 / 200 | 200 / 200 | 200 / 200 |
| comment-unclosed | 500 / 500 | 500 / 500 | 400 / 400 |
| single-quotes | 200 / 200 | 200 / 200 | 200 / 200 |
| unquoted-keys | 200 / 200 | 200 / 200 | 200 / 200 |
| unquoted-string | 500 / 500 | 500 / 500 | 400 / 400 |
| equals-separators | 200 / 200 | 200 / 200 | 200 / 200 |
| arrow-separators | 200 / 200 | 200 / 200 | 200 / 200 |
| trailing-object-comma | 500 / 500 | 500 / 500 | 400 / 400 |
| trailing-array-comma | 200 / 200 | 200 / 200 | 400 / 400 |
| leading-array-comma | 200 / 200 | 200 / 200 | 400 / 400 |
| array-semicolon | 200 / 200 | 200 / 200 | 400 / 400 |
| multiple-top-level | 500 / 500 | 500 / 500 | 400 / 400 |
| trailing-garbage | 500 / 500 | 500 / 500 | 400 / 400 |
| nonexecute-prefix | 200 / 200 | 200 / 200 | 200 / 200 |
| bom | 200 / 200 | 200 / 200 | 200 / 200 |
| invalid-escape | 500 / 500 | 500 / 500 | 400 / 400 |
| invalid-unicode-escape | 500 / 500 | 500 / 500 | 400 / 400 |
| short-unicode-escape | 500 / 500 | 500 / 500 | 400 / 400 |
| raw-newline-string | 200 / 200 | 200 / 200 | 200 / 200 |
| escaped-newline-string | 200 / 200 | 200 / 200 | 200 / 200 |
| eof-object | 500 / 500 | 500 / 500 | 400 / 400 |
| eof-key | 500 / 500 | 500 / 500 | 400 / 400 |
| eof-colon | 500 / 500 | 500 / 500 | 400 / 400 |
| eof-string | 500 / 500 | 500 / 500 | 400 / 400 |
| eof-array | 500 / 500 | 500 / 500 | 400 / 400 |
| missing-colon | 500 / 500 | 500 / 500 | 400 / 400 |
| missing-comma | 500 / 500 | 500 / 500 | 400 / 400 |
| duplicate-query | 200 / 200 | 200 / 200 | 200 / 200 |
| uppercase-null | 200 / 200 | 200 / 200 | 400 / 400 |
| timeout-leading-zero | 200 / 200 | 200 / 200 | 200 / 200 |
| timeout-single-quotes | 200 / 200 | 200 / 200 | 200 / 200 |
| timeout-invalid-escape | 500 / 500 | 500 / 500 | 400 / 400 |
| timeout-nan | 400 / 400 | 400 / 400 | 400 / 400 |
| timeout-plus-number | 200 / 200 | 200 / 200 | 200 / 200 |
| query-trailing-array | 200 / 200 | 200 / 200 | 400 / 400 |
| unpaired-surrogate-query | 200 / 200 | 200 / 200 | 200 / 200 |
| invalid-utf8 | 200 / 200 | 200 / 200 | 200 / 200 |

Reproduction: run `python3 -u docs/go-server-baseline/http-malformed-json-main/capture.py --out /tmp/new-gson-capture` from repository root using a new output directory to preserve the existing capture. Native tests: `go test -race ./internal/server -count=1` and `go vet ./internal/server`. `main-command.json` and `metadata.json` identify the exact jar, fixture hashes, process, and shutdown receipt.

The follow-up non-finite capture is in `../http-nonfinite-main`: non-finite successful evaluations fail during Gson response serialization as500; division by zero remains an execution-stage400. Native response encoding uses a typed serialization error so only this phase maps to500.

The capture script now requires a fresh `--out` directory and refuses an existing directory, to prevent accidental replacement of the saved oracle. This reproduction guard was added after the recorded capture; raw observations and logs remain unchanged.
