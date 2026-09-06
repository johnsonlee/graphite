# Attempt 7 — certified shortest trigram anchors

This candidate selects the shortest persisted trigram posting span for eligible
CONTAINS arms before exact string matching. It includes the necessary cold
completeness proof. It does not add signatures, a lowercase or response cache,
prefix/suffix/equality acceleration, a new node order, or query-text special cases.

## Exact candidate and evidence

Isolated native base: `4182478ee0575c3a359a47a152ed99edb4a4ede7` (integrated Attempt 6).
Frozen 69-file patch SHA-256:
`e3e6c413d129adb67097d122c8e883bbe6273996f5f02aa75450f21098341ed4`.
`candidate-source.json` hashes every source/evidence file; `candidate-freeze-readme.md`
retains the provider's original verification and limitations. Four production
files change: string_candidates.go, Store's proof state, and two new index/proof files.

Profile binary hashes:

- Base: `0f2d046a608f8d2b4dabe268d5df4c15407b5b6cda478dd902743a6863fac8d3`.
- Candidate: `4c6de7c06fff960e765fe1d833ecbdbfc0d9f8c7745c5a051a4c9609d3cf96d7`.
- Separate HTTP candidate: `372ba269c0b3b7699c310f94f981b97d1ba2412601d90bfa9202b0ec98601dcb`.

All performance observations use the same 64 real persisted shards under
`/tmp/pr113-exp037-fixture.nXn4fg`: 1,152 frozen files, 10,338,207,518 bytes,
19,431,891 nodes, 20,448,885 edges, 1,374,983 methods and 5,046,935 CallSites.
Both profile processes freshly hash every file and verify every catalog entry.
Synthetic fixtures are used only for semantic, corruption and lifecycle assertions.

## Eligibility, completeness and fallback

All Attempt 6 AST, first-clause, null/type, Annotation, MAPPED-only, cancellation
and candidate-order constraints remain. Each OR arm chooses its own path.
CONTAINS requires at least three UTF-16 units. Raw RHS additionally requires ASCII
and is lowercased only for lookup; LOWER predicates use the original RHS units.
Short/empty/raw-non-ASCII and other admitted operators retain dictionary matching.
The original predicate still checks candidate strings and emitted nodes exactly.

The reader searches span boundaries while holding its existing lifetime lock,
without copying every span. A missing hash is an available-empty result only
after proof; otherwise the shortest span is copied into owned memory. Hash
collisions merely add candidates, which exact matching removes. No mapped slice
escapes to the query evaluator.

The cold proof first requires Attempt 6's core/order/CSR certificate, then visits
every SID used by the four properties, performs whole-string Java ROOT lowercase,
and checks every required (UTF-16 trigram hash, SID) pair. Extra pairs are harmless.
Missing pairs cache an invalid proof and select the prior dictionary path.
Cancellation/Close propagate without publishing a completed proof; cached return,
waiters and publication use the same fixed lock order. Proofs belong only to the
same immutable Store instance and retain no transformed strings.

Cold work is measured inside the first query, including lowercasing, temporary
maps, directory copies and exact-key searches. The proof is not moved to an
unmeasured startup phase. Existing malformed-core/raw-projection gaps from main
are not repaired by this optimization. In the earlier 80-case corpus, 54 errors
have differing class/message, six main successes fail natively, and one scoped
raw result differs in provenance; these are not all raw-success differences.

## Correctness and independent integration

296 actual pinned-main observations (232 original + 64 mixed-OR), each stable over
three captures, are compared through enabled, forced-dictionary and forced-scan
paths on clean, valid-CRC missing-posting and extra-posting fixtures: 2,664 complete
columns/rows/error comparisons. Mutated sidecars are compared to clean main and
the corresponding native fallback; they are not claimed to be identical to
malformed-main behavior. UTF-16/surrogate, Greek context, collision, parameter,
Annotation/null, returned-copy and canceled/closed proof boundaries are covered.

Root independently rebuilds the exact frozen source in a clean archive. An
external necessary-condition test enumerates every admitted substring of the
fixture's 37 used strings: all 292 raw and 336 LOWER anchors retain the original
SID. Whole-module race/vet and that proof pass (`independent-verification.json`).

Integration starts at `bbdfae2a` and retains the later actual CandidateNode
`failNodeRead` mapping. Only string_candidates.go differs from the freeze by that
existing error mapping; the other 68 files are byte-identical. A separate final
archive passes whole-module race/vet and the same external proof, including the
existing consumed-tag tests. `integration-source.json` and
`integration-verification.json` record it. Isolated profile numbers are not a
measurement of this later integrated build.

The all-64 HTTP replay completes with 42/42 HTTP 200 responses, entire ordered-body
equality and no Content-Type/Retry-After differences against pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. Normal 60-second deadline and capacity
four remain; requests are sequential and the owned server is stopped on exit.
Lists/nulls remain significant. Incremental and final observations are retained.
The runner adds a single fetch-duration field solely to locate expensive shapes.

## Cold and warm diagnostic costs

Go 1.22.0, darwin/arm64, macOS 64 GB; GOGC/GOMEMLIMIT/GOMAXPROCS/GODEBUG unset.
Small packaging/HTML code correctness tasks may co-tenant; the HTML sampling burn
was deferred during this profile. Forced GC before/after each query is excluded
from its counters and CPU samples but preserves the certificates. These are
individual native-to-native instrumented observations, not main-relative P95.

| Query | Allocation bytes, base → candidate | GC cycles | CPU user+system, seconds | Execution+marshal, seconds |
|---|---:|---:|---:|---:|
| wrapped-zeroHitBroadContains, cold | 3,576,499,936 → 4,636,974,256 | 0 → 0 | 10.531 → 15.019 | 10.532 → 15.012 |
| identical wrapped repeat, warm | 990,094,088 → 1,022,744 | 0 → 0 | 1.285 → 0.004735 | 1.283561 → 0.002235 |
| raw four-property zero, warm | 111,623,440 → 739,272 | 0 → 0 | 0.163579 → 0.002629 | 0.161139 → 0.001273 |
| supplemental Method count | 2,441,489,976 → 2,441,488,008 | 0 → 0 | 0.798083 → 0.791816 | 0.797738 → 0.791407 |

The cold wrapped query regresses: about 1.06 GB additional allocation and 4.48 s
additional execution+marshal in this diagnostic. Request-end heap rises
9.86 → 10.92 GB. Warm wrapped heap falls 7.27 → 6.28 GB and raw 6.40 → 6.28 GB;
post-forced-GC heap remains about 6.28 GB. None is a peak-RSS or reduced-retention
claim. The control's -1,968 bytes and small timing change are not a performance
conclusion. Millisecond queries provide too few CPU profile samples to rank fine
hotspots; raw request counters and complete bodies remain the evidence.

Warm zero-hit allocation falls substantially, but positive/prefix work remains
expensive. Single HTTP fetch diagnostics include 18.98 s for
wrapped-firstLastGraphBimodalClassPrefix, 12.42 s for
 global-wide-wrapped-case-insensitive-distinct-dense, and 10.42 s for
 global-wide-distribution-broad-all-64. These are one observation each, with
co-tenancy and normal HTTP overhead, not P95. They identify the next profile work;
they do not establish a regression from a different binary or main.

## Reproduction and decision

Reconstruct the pinned base and exact patch, then use fresh output directories:

```sh
python3 docs/go-server-baseline/native64-trigram-anchor-attempt7/run-profile.py \
  --worktree /tmp/graphite-go-trigram-attempt7-4182478e \
  --out /tmp/graphite-attempt7-profile-new
python3 docs/go-server-baseline/native64-trigram-anchor-attempt7/replay-http.py \
  --binary /tmp/graphite-go-trigram-http-attempt7 \
  --out /tmp/graphite-attempt7-http-new \
  --source-identity docs/go-server-baseline/native64-trigram-anchor-attempt7/http-build.json
```

`profile-comparison.json`, per-side identities, raw profiles/receipts and
`pprof-commands.json` record exact commands and data. No failed response is removed
from the denominator. Keep the bounded anchor optimization for the verified warm
allocation reduction and complete fixed-workload correctness, with the explicit
cold regression. Full compatibility, cold/warm repeated paired main/native P95,
positive-query improvements and the overall 10× target remain unproven.

The initial integration whitespace check reported four trailing blanks in the
zero-sample `cpu-top.txt`/`cpu-cum.txt` outputs. Those exact raw pprof bytes are
preserved; four path-specific `.gitattributes` entries exempt only end-of-line
blanks. No profile, counter, result, or receipt was normalized.
