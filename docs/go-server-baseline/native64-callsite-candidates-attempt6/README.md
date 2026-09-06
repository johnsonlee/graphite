# Attempt 6 — certified CallSite string candidates

This candidate reduces decoded node traversal using the existing four CallSite
string-property CSR indexes. It still tests every relevant dictionary entry with
the existing exact string predicate, then unions postings and visits candidates
in original per-source byte-offset order. It does not use trigrams, signatures,
query-text special cases or cached query responses.

## Eligibility and failure behavior

Only the first clause of a branch can use this path: a mandatory, named, single
node MATCH with no path, relationship or inline properties, no label or one
CallSite/CallSiteNode label, and a whole WHERE consisting of OR-combined equality,
CONTAINS, STARTS WITH or ENDS WITH predicates on the four caller/callee fields.
The RHS must be a string literal or string parameter. Exact-arity toString,
coalesce(value, '') and at most one ROOT lowercase wrapper are recognized.
Unsupported clauses fall back in full. Untyped empty-on-null predicates with an
empty RHS and any selected graph containing Annotation nodes also fall back.
EAGER retains its loaded-data behavior and never enters this file-backed path.

All selected sources must be prepared before any candidate is published. A cold
certificate validates complete native node decoding, increasing CallSite offsets,
actual SID association and complete CSR membership. This prevents the optimization
from hiding failures that the previous native scan would consume. Missing or
invalid optional indexes fall back; cancellation and Store.Close propagate and
are not cached. Completed valid or invalid proofs live only with the immutable
Store. Candidate decoding holds the mapped-data lifetime lock. Original WHERE,
projection and limit evaluation still run on selected candidates.

This is not proof of full malformed-store compatibility with main. The preserved
80-case corrupt/edge corpus has 80/80 indexed-versus-forced-native-scan agreement,
but 61/80 differences from main: 60 bad-core/error or raw-index-projection cases,
and one scoped raw metadata case. Main can project some raw indexed fields without
fully decoding a malformed node. These existing native gaps remain explicit.

## Identity and verification

The isolated native base is `1c235916a930a9dd65ce2a68dfaee854a40e9d05`; it already
contains the optional index reader. The 127-file frozen patch is `candidate.patch`,
SHA-256 `22429f2b01531350bfd13c57eefb52c962a62e674353260d819a492dafb18e1e`.
`candidate-source.json` records every file. Six files change production behavior.
Base profile binary SHA-256 is
`93a1e61390705b27a10a995751723a1b1f6f0f82f03522359a8684f82e340fb2`;
candidate profile binary SHA-256 is
`18c3158ece6578fe4b793bbf7af750a1245b0a85920d2a207ac4e75dee315075`.
The separate HTTP build verifies all 127 frozen files; `http-build.json` and
`http/identity.json` record its exact binary and invocation.

The candidate passes 192 complete valid main query results with forced-scan
comparisons. Root independently reconstructs the frozen patch in a clean archive,
runs full-module race/vet, and checks 1,456 admitted predicate forms with 21,840
node-kind/CallSite-transform comparisons, including Unicode and isolated
surrogates. See `independent-verification.json` and the external proof source/log.

Root integration starts from `3feb4157`, retaining subsequent count conversion and
early-limit/lazy traversal fixes. The production difference from the freeze is
engine.go: a copied evaluator receives the clause ordinal before either ordinary
or early-limited MATCH. The initial archive found an intermittent cancellation
test failure: a threshold of 118 could be beyond the actual 117 checks because
map encounter order changes sort comparison counts. The preserved 40-run diagnosis
confirms that cancellation had not yet occurred in those failures. The test now
checks whether its threshold was actually reached and retains at least 100
asserted cancellation points. No production change was needed for this finding.

The final 125 other frozen files remain byte-identical; only engine.go and this
test differ. `integration-source-final.json` and `integration-verification-final.json`
record a fresh archive: 40 repetitions of the corrected test, full-module race/vet,
and the independent 21,840-comparison proof all pass. Initial failed logs and
receipts remain unchanged. The isolated profile is not a measurement of the later
integrated server.

All four profiled full outputs match, including a repeated wrapped query and a
supplemental Method-count control outside the original 42-query workload. The
full HTTP replay completes with 42/42 HTTP 200 responses, full ordered-body
equality and no checked header differences against pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. `http/summary.json` preserves the complete
denominator, and both incremental and final observations remain. The owned server
was stopped and the runner exited successfully. This is correctness evidence.

## Real 64-graph diagnostic

Both processes verify all 1,152 frozen files (10,338,207,518 bytes) and all 64
catalog entries under `/tmp/pr113-exp037-fixture.nXn4fg`: 19,431,891 nodes,
20,448,885 edges, 1,374,983 methods and 5,046,935 CallSites. Every graph has zero
Annotation nodes, independently verified by the preceding reader audit. No
synthetic graph is used for any performance measurement.

Go 1.22.0, darwin/arm64, macOS 64 GB; GOGC/GOMEMLIMIT/GOMAXPROCS/GODEBUG unset.
Host co-tenancy includes small JVM/Gradle/Go correctness jobs and affects timing.
The first wrapped query includes cold certificate and optional-index preparation;
the identical second query retains the proof in the same process. Forced GC before
and after each query is excluded from request counters and CPU sampling. Those
collections do not discard the certificate. No response result is cached.

| Query | Allocation bytes, base → candidate | GC cycles | CPU user+system, seconds | Execution+marshal, seconds |
|---|---:|---:|---:|---:|
| wrapped-zeroHitBroadContains, cold | 20,063,190,048 → 3,576,489,880 | 3 → 0 | 57.907 → 10.629 | 44.656 → 10.612 |
| wrapped-zeroHitBroadContains-repeat, warm | 20,061,635,120 → 990,091,056 | 3 → 0 | 57.816 → 1.282 | 44.479 → 1.279 |
| global-wide-four-properties-zero, warm | 3,661,675,032 → 111,622,880 | 0 → 0 | 13.540 → 0.165 | 13.542 → 0.163 |
| supplemental-method-count | 2,441,489,624 → 2,441,489,720 | 0 → 0 | 0.790 → 0.779 | 0.766 → 0.777 |

Cold wrapped request-end heap rises 8.90 → 9.86 GB without a collection; warm
wrapped falls 8.28 → 7.27 GB and raw four-property falls 9.95 → 6.40 GB.
Post-forced-GC heap remains approximately 6.28 GB. These are not peak RSS or a
claim of reduced retained heap. The control adds 96 bytes; its single timing does
not establish a meaningful improvement or regression. These observations are
native-to-native allocation/CPU diagnostics, not HTTP P95, repeated paired results
or the main-relative 10× acceptance measurement.

The warm wrapped CPU profile attributes 0.59 s cumulatively to Java-compatible
case conversion out of 1.07 s sampled CPU. The cold profile includes 5.90 s flat
runtime.madvise and 3.17 s cumulative certificate work out of 10.22 s sampled CPU;
cumulative paths overlap and must not be added. Short raw-query sampling is sparse.
`profile-comparison.json`, raw receipts, and each side's `pprof-commands.json`
retain exact counters and derivation commands.

## Reproduction

Reconstruct the pinned base and exact patch in separate worktrees. Use fresh output
directories and the same frozen real fixture. The config runs four queries in order.

```sh
python3 docs/go-server-baseline/native64-callsite-candidates-attempt6/run-profile.py \
  --worktree /tmp/graphite-go-callsite-candidates-attempt6-1c235916 \
  --out /tmp/graphite-attempt6-profile-new
python3 docs/go-server-baseline/native64-callsite-candidates-attempt6/replay-http.py \
  --binary /tmp/graphite-go-callsite-candidates-http-attempt6 \
  --out /tmp/graphite-attempt6-http-new \
  --source-identity docs/go-server-baseline/native64-callsite-candidates-attempt6/http-build.json
```

The HTTP runner uses the normal 60-second query deadline, admission capacity four
and sequential requests. It compares whole bodies with list order and nulls
preserved, plus Content-Type/Retry-After. It owns and stops its server. Failed or
partial responses remain evidence; none may be removed from the denominator.

## Decision

Keep the narrow candidate for its verified allocation reduction and complete
fixed-workload HTTP equality. Integrated archive race/vet and independent proof
pass. Known malformed-store/main-order compatibility gaps, repeated cold/warm
main/native P95, and the overall 10× target remain open.
