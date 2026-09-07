# Independent indexed DISTINCT integration review

The frozen DISTINCT feature was integrated into root commit `1d23843ac1e79e00bacd87befa60980b65181293` in the isolated worktree `/tmp/graphite-go-distinct-integration-1d23843a`. No root production file, original contract freeze, or ordinary-projection worktree was modified; no commit was made.

Contract manifest SHA-256: `b51e325e99ee52cbef7ce30f92d13a5bcf48e63dc8ed2e9a96870b4b86fbf5fe`.
Contract patch SHA-256: `5fee9b67291155baddb3a1db4889d0980e299bd1a0c4a5c7923ee671267fa787`.

The only three-way conflict was the Store struct: the current `trigramProof` field and incoming `distinctProjection` field were both retained. A7 candidate/trigram behavior, A8 ASCII case handling, and CandidateNode's `failNodeRead` consumption mapping remain in place. `verification.json` records preserved file hashes; all runtime source files remained byte-identical while HTTP replay ran.

## Concrete regression found and corrected

The original feature panicked on this previously supported empty-store API call:

```go
Execute(ctx, nil, "MATCH (n:CallSite) WHERE n.caller_class CONTAINS 'a' RETURN DISTINCT n.caller_class AS x LIMIT 1", nil, -1)
```

`indexedDistinct` dereferenced `source.Store.Mode`. A control using the original root engine returned columns `["x"]` and empty rows. The independent correction declines scoped indexed DISTINCT when `graph == nil`, letting the existing executor retain that contract. Empty cross-source execution is also checked. `nil-store-before.log`, `nil-store-base.log`, and `nil-store-after.log` preserve the exact before/control/after evidence. This guard is the only production change beyond the original feature plus conflict resolution.

`independent-fix.patch` contains this guard and four independent test files. `integrated.patch` is the entire feature, integration resolution, correction, and tests relative to the stated root commit. `candidate.patch` remains in the original contract freeze, unchanged.

## Oracle denominator and HTTP outcome

All 1,048 oracle pairs were replayed, not only the eligible subset. The 17 complete native evidence files are structurally identical to the original contract acceptance files, and all 1,048 pair classifications match exactly.

| Classification | Cases |
|---|---:|
| Eligible: complete match | 580 |
| Declined: complete match | 159 |
| Declined: both error, different class/message | 180 |
| Declined: main success, native error | 122 |
| Declined: both success, different result | 7 |
| Total | 1,048 |

Thus 739/1,048 match overall. The 309 declined differences remain outstanding; this is not a claim that all 1,048 match main. See `oracle-summary.json`, `oracle-native-final/`, and `oracle-identity-verification.json`.

The unchanged Attempt 7 harness completed all 42 HTTP cases on all 64 real graphs at port 18858. Every response was HTTP 200 and equal to the fixed main response: complete JSON values, exact decimal numbers, array order/nulls, and selected Content-Type/Retry-After headers. No dynamic-pointer masks were used. Server timeout remained the default 60,000 ms; the existing harness transport timeout is 120 seconds. `http/catalog.json` verifies graph counts and totals, and `http/identity.json` identifies every graph, executable argument, source/binary/main-response hash and relevant environment. `http/observations.json` retains every full body. The harness stopped its server after completion.

Inherited elapsed fields are correctness-run diagnostics only. No P95, optimization, throughput or performance claim is made.

## Independent review and tests

- Nullable error messages: `query.Error.JavaMessage()` preserves nil versus an empty string; `Error()` uses `Query execution failed` only for nil. Independent HTTP-boundary tests assert exact status 400 and entire error/code maps for nil, empty and nonempty messages. Existing error strings are unchanged.
- Lifetime locks: selective mapped reads and offset preparation use the existing index lifetime lock. Index loading releases that lock before content-identity derivation, so the new four-SID/offset calls do not nest it. Close marks closed and clears DISTINCT state while holding the same lock, releases it, then waits for an in-progress loader. Publication checks context/closed under that lock. Returned index handles recheck the lifetime lock before accessing bytes. The feature's retained marker is separate from both A6 and A7 proof state.
- Missing identity versus full-node certification: an independent persisted fixture mutation changes the node tag to 255 and removes the content identity file. Four-SID identity/index initialization still succeeds because the tag is not consumed there. It does not publish a full-node proof; A6 certification returns false and actual CandidateNode consumption still reports typed UnknownNodeTagError. This directly checks that the shared identity change cannot bypass A6's full decode requirement. The first test draft accidentally changed the ID byte instead of the tag; that logged node-index mismatch is retained, and the corrected `offset+4` mutation passes.
- Scheduling: independent channel-coordinated tests invoke the 40-task ordered-prefix path. Once the first selected result satisfies LIMIT, speculative suffix failures do not replace it, cancellation reaches started suffix tasks, and all started tasks have completed before return. A corresponding unordered required-source failure propagates while joining all started tasks. These tests use synchronization and concrete results, with no timing threshold.
- The 1,048 main corpus separately covers actual 9/40-source split storage, corrupt required reads, selected-tuple provenance from later sources, and graphId-incompatible late source exclusion. Review confirmed that selection uses the contiguous source prefix and that later selected-value provenance probes do not initialize irrelevant raw targets. No custom JVM scheduling-property override equivalence is claimed.

The complete native module passed `go test -race ./...` and `go vet ./...` after integration and again after the nil-store correction. The final all-module run includes the independent scheduler, HTTP error, and raw identity/certificate tests. `git diff --check HEAD` also passed. The final Go test run may reuse valid Go test-cache entries; the original integration and post-fix query replay logs are both preserved.

## Commands and identities

From `graphite-server`:

```sh
INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-distinct-integration-1d23843a/distinct-integration-freeze/oracle-native-final go test -race ./...
go vet ./...
go build -trimpath -buildvcs=false -o ../distinct-integration-freeze/graphite-server ./cmd/graphite-server
```

From the worktree root:

```sh
python3 distinct-integration-freeze/summarize-integration.py
env -u GRAPHITE_NATIVE_CPU_PROFILE -u GRAPHITE_PROFILE \
  python3 docs/go-server-baseline/native64-trigram-anchor-attempt7/replay-http.py \
  --binary distinct-integration-freeze/graphite-server \
  --out distinct-integration-freeze/http \
  --source-identity distinct-integration-freeze/source-identity.json --port 18858
```

The external summarizer changes only evidence paths, retaining the original classification logic; it does not rewrite frozen main or contract observations. The executable includes the root profiling module but profiling was explicitly disabled for this run. Independent test files added after the binary build do not enter the executable; `source-identity.json` records its build source, and `final-source-manifest.json` adds the final tests.

- Binary SHA-256: `943b07be4192978b307f72f1d403ea4db5c485fe8b3072d52ba457de573543e4`.
- Build source identity SHA-256: `eb8690ad611d862f530c307777567178e8abd95fafe7de97e75763f0b95b0338`.
- Integrated patch SHA-256: `8b51d784f8d70674b24ef45ec4a1b4140086f416c0b3dbffa688dc51a3cc50c4`.
- Independent guard/tests patch SHA-256: `0877d7df581315cb3e83f744942db92686e52832e4ef37890880fcb72cb5f108`.

Ordinary indexed projection, retained-history compatibility outside DISTINCT, the 309 declined mismatches, and main's unsafe close-race behavior remain outside this completed integration review.
