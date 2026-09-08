# Native quoted-contains matcher candidate

Baseline Go is `0d055463` with the independent Java quote semantic correction;
original main is `4e328b0109e13c896b74004823fb049fcb19251a`.

The full real64 workload has three default-flag regex queries of the form
`.*\Qliteral\E.*`. The previous matcher decodes every input to Java code points
and runs the general backtracking machinery, even for ASCII substring searches.
The candidate retains ordinary compilation and its errors, then installs a
literal-search plan only for this exact pattern form: nonempty ASCII literal,
no CR/LF and no embedded quote terminator. Every other pattern retains the
ordinary matcher. Non-ASCII, WTF8 and invalid UTF8 inputs also fall back.

A compiled immutable KMP prefix table supports linear matching for arbitrary
literal lengths and avoids per-match decoder/matcher allocation in this domain.
The scan checks the whole input even after a hit, since a later CR/LF makes Java's
whole-string match fail. Context checks cover plan construction, scans and
fallback loops. The change does not alter candidate source selection, property
reads, eager expression/OR ordering, compile-cache behavior or error propagation.

Tests cover all ASCII bytes and line endings, overlapping and long repeated
literals, cancellation before and during work, concurrent use, Unicode/WTF8
fallback, invalid patterns and exact actual-Java results. Public query tests
cover null/nonstring operands, eager left-to-right errors with no partial output,
parameter reuse and eviction/reuse across more than 256 compiled patterns.
Full-module race tests and vet pass with 2,542 recorded inputs unchanged.

Separate full three-state real64 captures compare every original case, public
result and seven graph-state fields with pinned main. Their terminal receipts and
archive verification are in `real64/` and `correctness-audit.json`. These retain
the original failing case and do not turn warm prewarm into a formal warm replay.

The same diagnostic command/profiler wrapper runs all 1,267 original cases before
and after the change. Exact TotalAlloc bracket deltas for regex-or-zero and
regex-or-targeted fall from 21,033,952,776 to 2,326,225,432 bytes and from
6,058,040,768 to 743,596,368 bytes. Process CPU falls from 34.547250 to 11.178630 s
and from 12.404417 to 3.527174 s. These include profiler bookkeeping and are
independent diagnostic observations. The earlier profile precedes the functional
quote correction; exact source archives preserve that difference.

All clocks from profiled invocations are contaminated and excluded from P95
acceptance. Sampled heap/allocation deltas can lag GC; the reported allocation
totals use process MemStats counters. Dense has zero CPU samples and supports no
stack attribution. See `profile-comparison.json` and each profile README for full
conditions, counters, raw profiles and limitations.

Three controlled main/Go pairs complete from immutable measurement source
`8677aff8f9b7a5e575879b698546c38d7af4a48c`. The incremental Git bundle in
`timing/measurement-source.bundle` preserves that commit before its final evidence
amend. Builds, tests and profiling terminate before the timed invocations; the
six runtimes execute serially on separate audited graph clones. Every runtime
retains all 1,267 original cases, with 1,266 successes and the original failure.
Both complete prewarm ledgers remain separate from the diagnostic warm replay.

| State (one sample per case/runtime) | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Candidate Go slower than main |
| --- | ---: | ---: | ---: | ---: |
| cold | 74.301 | 56.654 | 26.836 | 937 / 1266 |
| startup-prepared | 73.715 | 56.084 | 24.659 | 926 / 1266 |
| warm-after-failed-prewarm (diagnostic) | 67.763 | 50.492 | 19.443 | 1180 / 1266 |

The two slow regex cases now take about 10.88–10.92 s and 3.45–3.47 s in Go,
compared with about 24.48–24.58 s and 7.36–7.54 s in the previous Go campaign.
Their paired main observations are about 12.59–14.40 s and 4.09–4.50 s. These are
independent single observations, not per-case latency P95 or statistical
regression estimates. The previous campaign precedes the independent quote
semantic correction. Every old/new case point observation remains in the archive.

Keep the matcher plan: complete observed query/state behavior is preserved and
all three timing states show lower slow-regex and aggregate success timers,
consistent with the removed decoder/matcher allocation and CPU stacks. This does
not establish the full performance target. Six-term wrapped OR remains around
3.1 s in Go versus about 55 ms for main's zero case; main's generic raw-string
filtering path needs a separate fidelity and performance investigation.

Main retains its original resource sampler: process CPU totals are 84.539,
67.775 and 38.721 s; peak used heaps are 6,250,444,456, 5,928,016,904 and
6,497,240,048 bytes; GC counts/times are 102/201 ms, 43/107 ms and 33/85 ms.
Go's formal driver still lacks equivalent sampled resource/work counters.
The earlier Go diagnostic profiles are labelled separately and cannot be used as
formal comparative process metrics.

Reproduction commands from the repository root, with fresh external outputs:

```
python3 docs/go-server-baseline/native64-p95-sampling/prepare.py --output /Users/johnsonlee/.codex/benchmarks/graphite/quoted-contains-8677aff8-build-v1
python3 docs/go-server-baseline/native64-p95-sampling/run.py --build /Users/johnsonlee/.codex/benchmarks/graphite/quoted-contains-8677aff8-build-v1 --output /Users/johnsonlee/.codex/benchmarks/graphite/quoted-contains-8677aff8-timing-v1 --pairs 1
```

The environment is Darwin 23.3.0 arm64, 16 logical CPUs, Go 1.22.0 and pinned
Java17 at -Xmx8g. Runtime override environment variables remain unset. This is
one sample per case/runtime/state; all 3,801 summary rows are insufficient for
P95, and the summarizer exits 1 with acceptance unproven. The required 200-sample
per-case P95, full 10x target, formal original warm, equivalent work/resource
accounting, server fidelity and required PR benchmark gates remain open.
