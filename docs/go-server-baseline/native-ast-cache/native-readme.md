# Native parsed-query cache

This candidate ports main's successful parsed-query LRU to native Go parsing.
The baseline is `bdcbd969917529f31b8ade101bae1ee15d89fa93` (engine bytes equal
`3b5ecae5c9a4c1425f18a3b5af3e373b41a184eb`). Original main is
`4e328b0109e13c896b74004823fb049fcb19251a`.

The original real64 setup parses all 1,267 cases before execution. These contain
750 distinct raw queries, all of which fit the 1,024-entry and 16 MiB cache under
the benchmark configuration. Main already reuses these ASTs during execution;
native previously lexed, parsed and converted every query again. The hypothesis
is that retaining ASTs removes that repeated work across the complete workload.
It does not predict that parsing accounts for the expensive graph-query tails.

Only successful nonblank raw query strings are cached. Semicolon child queries
use the same cache and preserve original partial-success behavior. Entries use
access-order eviction, with the original UTF-16 and expanded clause-size estimate.
The byte cap is initialized lazily to min(16 MiB, Go runtime soft memory limit /
128). Go's soft limit is not the JVM maximum heap; both this unlimited Go process
and the benchmark's -Xmx8g use the 16 MiB ceiling. Memory-setting equivalence across
arbitrary Java/Go configurations is not established.

Cached trees are private. Public Parse and ParseContext return independent deep
copies of all parser-produced mutable containers and hop pointers. Parser literal
nodes contain scalars or nil; list/map syntax has explicit expression containers.
Parameters and execution results are never stored in the parser cache. The
executor still parses before checking query cancellation or validating clauses.
ParseContext checks cancellation on cache entry, within copying and on completion;
failed or canceled results do not enter the cache. Main reference identity and
mutation exceptions are explicitly adapted to owned mutable Go AST values.

Independent tests compare actual original-main cache transitions, complete AST
values across all 750 unique real64 queries, LRU entry/byte pressure, oversized
queries, raw/semicolon keys, caller mutation isolation, concurrent parsing and
execution with distinct parameters, parse-error ordering, and cancellation during
a cached single-clause copy. Full-module race tests and go vet passed with 2,535
recorded inputs unchanged. Actual-main oracle coverage is documented separately
in README.md. The first focused test compile failed due to an int/int64 test
constant; it was fixed before the passing recorded full checks. Independent
review also identified and fixed missing nested-copy cancellation checks before
those checks. No failed performance trials have been discarded.

Reproduce full native checks with `python3 run-tests.py` from this directory in a
fresh checkout/output (existing evidence is deliberately not overwritten).
`python3 run-real64.py` from the repository root freezes a separate complete native
module and replays all original cases and seven graph-state fields against the
archived pinned-main controls. Results and unchanged-input receipts live in real64/.

Cold replay, warm prewarm and startup-prepared replay each matched all 1,267
original cases, including 1,266 successful results and the original failure.
Seven graph-state fields match across 486,848 observations, and all 1,152 original
graph files remain unchanged per runtime. All three comparator exits are zero;
runtime exits remain one for the original all-success gate. The separate frozen
module contains 2,529 files, unchanged through every capture. Compressed raw
streams reproduce the original bytes exactly. Warm is a prewarm comparison; its
formal correctness manifest is not produced.

The candidate's three paired timing runs completed from frozen source commit
`6182822983971ab1ec9dbee1023665a60c36e465`. The incremental Git bundle retained
with the timing evidence preserves that measurement commit even after this
attempt's evidence is folded into its final commit. All 279 internal Go source
files match the frozen build and measured commit. There are no code changes
between the correctness and timing captures.

| State (one sample per case/runtime) | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Candidate Go slower than main |
| --- | ---: | ---: | ---: | ---: |
| cold | 74.280 | 74.301 | 24.640 | 981 / 1266 |
| startup-prepared | 73.782 | 73.715 | 24.990 | 910 / 1266 |
| warm-after-failed-prewarm (diagnostic) | 67.537 | 67.763 | 19.441 | 1221 / 1266 |

The median across successful cases of previous-Go / candidate-Go single-sample
latency ratios is respectively 2.288, 2.256 and 2.262. These are distributions of
point ratios across cases, not latency P95 or repeated-sample speedup estimates.
Whole-workload sums are practically unchanged in these observations; no
statistical regression/no-regression conclusion is supported. The two most
expensive native regex cases still cost about 24.5s and 7.4s. Cache reuse is kept
because it aligns the original successful-AST lifecycle and removes repeated
parser work, with improved point estimates for many cases and no correctness
or graph-state differences. It does not achieve the full performance target.

Main retains its original resource sampler. Its process CPU totals for these
three runs are 75.198s, 70.677s and 39.085s; peak used heap is 5,894,827,936,
5,979,003,520 and 6,260,221,704 bytes, and GC counts/times are 47/105ms,
57/135ms and 35/63ms. Raw counters are retained. Go's timing driver does not yet
provide equivalent sampled CPU/heap/RSS/GC counters; no native CPU, allocation
or memory speedup is claimed. Main's original counters containing pooled case
percentiles are also retained as raw evidence, but are not per-case P95.

Commands (from the repository root, using fresh output directories):

```
python3 docs/go-server-baseline/native64-p95-sampling/prepare.py --output /Users/johnsonlee/.codex/benchmarks/graphite/ast-cache-61828229-build-v1
python3 docs/go-server-baseline/native64-p95-sampling/run.py --build /Users/johnsonlee/.codex/benchmarks/graphite/ast-cache-61828229-build-v1 --output /Users/johnsonlee/.codex/benchmarks/graphite/ast-cache-61828229-timing-v1 --pairs 1
```

Environment: Darwin 23.3.0 arm64, 16 logical CPUs, Go 1.22.0 and the pinned Java17
runtime at -Xmx8g. GOGC/GOMEMLIMIT/GOMAXPROCS/GODEBUG and Java option environment
overrides are unset. Each runtime uses a fresh audited clone of all 64 real
persisted graphs, runs the complete original ordered workload, and has independent
before/after fixture/input verification. Builds, tests and profiling do not
concur with timed invocations. All three pair verifications pass; all six runtime
exits remain one for the original failure. The warm prewarm and diagnostic replay
are both complete and kept separate.

The summarizer retains all 3,801 state/case rows and returns failure because
samples are insufficient and acceptance remains ineligible. Baseline and candidate
raw samples, hashes, build closure and limitations are retained. Full 10x P95,
formal original warm, server fidelity and required PR benchmark gates remain open.
