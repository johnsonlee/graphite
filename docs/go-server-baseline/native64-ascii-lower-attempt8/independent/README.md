# Independent Attempt 8 validation

The candidate was built from a clean `git archive de0608f075174833a06f31eb3214b3ba9c4e0917` plus exactly the three candidate files listed in `source-identity.json`. Only `internal/javastring/unicode.go` changes production code. The archive contains neither diagnostic helper nor the later profiling module. No production file was modified during review, and all recorded source hashes were rechecked after validation.

## Review conclusion

No semantic counterexample was found in this bounded review. The fast path is restricted to lowercase calls where every input byte is ASCII. Java ROOT lowercase on those code points changes only `A` through `Z`; all control characters, NUL, punctuation, lowercase letters and DEL stay intact. Complete qualification precedes output, so a late non-ASCII byte cannot cause a partially transformed fallback. Uppercase, valid non-ASCII, malformed UTF-8 and WTF-8 all execute the unchanged original case mapping, including contextual Greek sigma and isolated surrogate preservation.

Callback count/order is preserved: one callback per consumed ASCII code point, zero for an empty string. Panic from any callback propagates at the same ordinal before that character is emitted; no callback is swallowed. Nil callbacks remain allowed. The new qualification scan does not poll cancellation. The old UTF16/CodePoints preparation also did not poll, so this preserves callback points, not a wall-clock cancellation bound. Allocation failure behavior is not asserted equivalent.

## Independent evidence

- `independent_ascii_test.go` imports the old Case body directly from the pinned base as a control, rather than calculating expected results with the candidate. It compares 20,544 concrete output/callback-count pairs across lower/upper, including all individual byte values, 10,000 deterministic mixed ASCII/arbitrary-byte strings, contextual Unicode, supplementary code points, malformed UTF-8 and WTF-8; nil callbacks are checked too.
- It also compares 110 concrete return/panic/callback-count outcomes at every cancellation ordinal for nine representative inputs in both case modes. These tests passed under `-race`.
- The original `javastring` test suite passed independently under `-race`, including the supplied 16,384-row Java 17 oracle. This review did not regenerate that Java oracle.
- `go vet ./...` passed on the clean native module without overlays.
- The unchanged Attempt 7 replay harness ran all 42 requests against all 64 real graphs, port 18857, using the default server timeout of 60,000 ms. All returned HTTP 200; entire JSON responses, exact decimal numeric values, list order/nulls, and selected Content-Type/Retry-After headers equal the fixed main observations. There were no dynamic-pointer masks. Transport timeout is the existing harness's 120 seconds; the server timeout was not raised.

`http/identity.json` records the exact executable command, 64 graph paths, binary/source/main-response SHA-256 values and relevant environment. `http/catalog.json` verifies 64 graph counts/totals; `http/observations.json` preserves every full main and native response, and `http/summary.json` has 42 complete cases and zero differences. The server was terminated by the harness. Elapsed fields inherited from the harness are diagnostics only: this is no benchmark, P95 result or new optimization claim.

## Commands

From the archived `graphite-server` directory:

```sh
go build -trimpath -buildvcs=false -o ../independent-freeze/graphite-server ./cmd/graphite-server
go test -race -vet=off -count=1 -overlay ../independent-freeze/test-overlay.json -run TestIndependentASCIILower -v ./internal/javastring
go test -race -count=1 ./internal/javastring
go vet ./...
```

From the archive root:

```sh
python3 docs/go-server-baseline/native64-trigram-anchor-attempt7/replay-http.py \
  --binary independent-freeze/graphite-server --out independent-freeze/http \
  --source-identity independent-freeze/source-identity.json --port 18857
```

The external test overlay initially used `/tmp` rather than macOS's canonical `/private/tmp`, producing a no-tests result. After fixing that path, Go 1.22's overlay vet reader used the replaced file's old imports; the final overlay test command therefore disables its vet phase. Both initial logs are preserved. Unmodified module vet ran separately and passed. An initial binary build accidentally used the source candidate checkout as cwd; its newly created artifact was removed, and the validated binary was independently rebuilt from this archive. No source or prior freeze was changed.

Binary SHA-256: `478ab0912560f58be8d1e9d0ff4a9ffb9dff169755dc9d95689a4b37c881fb5c`.
Source identity SHA-256: `cd6e780a81213591249afd943296b6c57ed96e169b06f9e22645addb8bd8c695`.
