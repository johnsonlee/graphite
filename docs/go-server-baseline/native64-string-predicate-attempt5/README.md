# Attempt 5 — avoid UTF-16 arrays for well-formed string predicates

The candidate uses UTF-8 byte matching for STARTS WITH, ENDS WITH and CONTAINS
(and their NOT forms) only when both operands are valid UTF-8. A valid UTF-16
needle cannot begin with an isolated low surrogate or end with an isolated high
surrogate, so its matches align with complete code points, as UTF-8 matches do.
Either malformed/WTF-8 operand keeps the existing UTF-16 path. Operand evaluation,
null/type behavior and NOT inversion retain their existing order. Cancellation is
checked before and after the standard-library operation; that operation is not
internally cancellable, and no maximum cancellation latency is claimed.

## Source and data identity

The isolated base is `4124bfc44bedd7915e238b7a08f2524b48ed47b6` plus the frozen
Attempt 4 patch `6084e090235c6f6505e17e05fafe657023ba148b1d70667be20454dd13d6955a`.
`effective-base.json` ties this to Attempt 4's candidate profile and its later
root integration `f85418822fbc01e902e08514b4e25aea2a517ef2`.

The five-file delta is `candidate-delta.patch`, SHA-256
`6f3b553bc33ff58d5fc7701156a49406e61fab070c297efd9dbe27a6698f973d`;
only `internal/query/eval.go` changes production behavior. `candidate-source.json`
is the original pre-review freeze; its pending review field is superseded by
`../string-predicate-readonly-audit/verification.json` and
`root-integration-verification.json`, without rewriting that original identity.
The profile binary SHA-256 is
`3f45fb79dea5b40f331a96bb784e2e6975341be7320d764ba950c977e8110a5b`.
The HTTP binary has its separate exact hash and command in `full42/identity.json`.

Every performance observation uses all 64 real persisted shards under
`/tmp/pr113-exp037-fixture.nXn4fg`: 19,431,891 nodes, 20,448,885 edges,
1,374,983 methods and 5,046,935 CallSites. All 1,152 frozen files (10,338,207,518
bytes) were freshly hash-verified before this profile; all per-graph catalog
counts were checked before profile and HTTP replay. Synthetic fixtures are used
only for semantic correctness, never performance.

## Correctness

The frozen candidate passes 252 pinned-main JVM queries, each repeated three
times, plus 3,456 UTF-16 definition comparisons. A separate read-only audit passes
24,576 definition comparisons, 60 operand/null/error-order cases and 12 cancellation
boundary cases. Root independently applies the exact delta to a clean archive of
its integrated production source and passes full-module `go test -race ./...` and
`go vet ./...`; the five final file hashes match the freeze.

All three profiled complete results match their expected bodies. The Method
count is an additional control, outside the original 42-query HTTP workload.
The default-deadline HTTP replay passes all 42/42 queries with HTTP 200, complete
response equality and no checked Content-Type/Retry-After differences. The runner
exits successfully and stops its owned server; `full42/summary.json` records the
complete denominator. The first query that timed out in Attempt 4 now passes.
JSON list order and nulls are preserved by the comparator; rows are not sorted.
Finite oracle coverage does not establish complete Cypher compatibility.

## Single-run allocation/CPU diagnostic

The comparison below uses the preceding frozen Attempt 4 candidate as its native
baseline, not main. Raw receipts and profiles remain under `candidate/profiles/`;
`profile-comparison.json` records the exact source receipt hashes. Go 1.22.0,
darwin/arm64, on the same macOS 64 GB host; GOGC/GOMEMLIMIT/GOMAXPROCS/GODEBUG
are unset. Profiling overhead and host co-tenancy affect durations.

| Query | Allocation bytes, base → candidate | GC cycles | CPU user+system, seconds | Execution+marshal, seconds |
|---|---:|---:|---:|---:|
| wrapped-zeroHitBroadContains | 42,656,840,592 → 20,063,190,576 | 7 → 3 | 93.482 → 57.650 | 62.580 → 44.367 |
| global-wide-four-properties-zero | 11,994,716,656 → 3,661,645,640 | 2 → 0 | 30.540 → 13.520 | 21.677 → 13.532 |
| supplemental-method-count | 2,441,488,968 → 2,441,489,352 | 0 → 0 | 0.792 → 0.769 | 0.778 → 0.765 |

Wrapped allocation falls about 53%; raw four-property allocation falls about 69%.
Request-end heap rises from 7.39 to 9.18 GB and 6.61 to 9.95 GB respectively,
while post-forced-GC heap stays around 6.28 GB. Fewer intervening collections can
leave more garbage at the request boundary; allocation reduction is not a claim
of reduced retained heap or peak RSS. GC before/after the request is excluded
from request deltas and CPU sampling. The control's +384 bytes is not evidence
of a meaningful latency regression or improvement. These are individual
instrumented observations, not P95, cold/warm samples or a 10× acceptance result.

## Reproduction

Use new output directories; the helpers reject an existing output path.
Reconstruct the frozen isolated source using the base and two exact patches
above before running:

```sh
python3 docs/go-server-baseline/native64-string-predicate-attempt5/run-profile.py \
  --worktree /tmp/graphite-go-string-predicate-attempt5 \
  --out /tmp/graphite-attempt5-profile-new
python3 docs/go-server-baseline/native64-string-predicate-attempt5/replay-http.py \
  --binary /tmp/graphite-go-string-predicate-http-attempt5 \
  --out /tmp/graphite-attempt5-http-new \
  --source-identity docs/go-server-baseline/native64-string-predicate-attempt5/candidate-source.json
```

The helper records actual binary/source/config identity and all raw responses.
`candidate/pprof-commands.json` records derived profile commands with flags before
the binary argument. Attempt 4's HTTP 504 record remains unchanged. No failed
request is dropped or counted as a successful latency result. Repeated paired
main/native measurements and the overall functional/10× P95 goal remain pending.

## Decision

Keep this narrowly scoped string-predicate optimization for its verified allocation
reduction and complete fixed-workload HTTP parity. This does not close known
compatibility gaps outside that workload or establish any main-relative P95 gain.
