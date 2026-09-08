# Attempt 21: original filtered string COUNT execution

The previous complete real64 pilot exposed an 11-second first COUNT query.
The baseline profile in `../native64-filtered-count-profile/` attributes 94.97%
of CPU samples to generic candidate preparation, including full-node and trigram
certificates. Actual pinned main uses posting aggregation for eligible filtered
COUNT queries. This change ports that public path, including admission, source
routing, residual fallback, DISTINCT sparsity selection and result provenance.
It does not remove certificates from their existing generic consumers.

The Go base is `9237131f`; the candidate consists of the five production/test
files recorded in `tests/receipt.json`. The timing driver is unchanged from that
base. `final-source.json` freezes the entire 2,517-file module source used by
the final correctness captures. The main reference remains
`4e328b0109e13c896b74004823fb049fcb19251a` and its original Explore JAR.
All performance observations use the real persisted 64-graph corpus with
1,152 original files, manifest SHA-256
`3084fd51040494ea79d72022fb46b004b15ac6475b4b4ff748adf8a443edf9cf`.
Each runtime receives its own copy; the reference is never opened by a runtime.

## Correctness

| Final capture | Original cases | Matching successful results | Original errors | Matching graph/state observations |
| --- | ---: | ---: | ---: | ---: |
| Cold replay | 1,267 | 1,266 | 1 | 162,304 |
| Warm prewarm only | 1,267 | 1,266 | 1 | 162,240 |
| Startup-prepared replay | 1,267 | 1,266 | 1 | 162,304 |

Definitions, parameters, source selection/order, query order and original result
constraints are unchanged. The seven checked state fields have zero differences
over 486,848 graph observations. All original graph files remain unchanged.
`final/controller.json` records terminal runtime exit 1 and comparator exit 0 for
each state. The original `four-or-graph-id-targeted` failure remains in every
run and fails the original all-success gate. Warm stops during prewarm and never
enters formal replay. The original error observer additionally exposes a fully
qualified `errorClass`; the native observer does not. This difference is retained
in each comparison rather than represented as byte-identical observer output.

The initial cold and warm candidate captures in this directory preserve two
provenance-order mismatches each, at dense COUNT cases 840 and 843. Count values
were correct. Actual main materializes provenance in Java UTF-16 order; the
candidate now applies that order without changing source task execution order.
Four additional main scenarios cover nonordered IDs, supplementary characters,
private-use characters, zero counts and DISTINCT. All eight new observations
failed before the fix and match afterward. Initial failed captures are retained.

`../native-filtered-string-aggregation/` contains 194 actual-main scenarios,
388 public observations, nine posting primitive observations and 400 separate
error-scheduling observations. The final candidate matches 380 of 388 public
observations; the eight remaining differences are explicitly recorded:
six duplicate-source constructor error-format differences and two existing
generic malformed unknown-label differences. The test excludes those known
gaps; its green result does not prove 388/388 equivalence. An independent control
with the old engine dispatch has 72 differences on the same corpus, versus
eight for the candidate. No performance claim uses these small synthetic fixtures.

Successful and error state checks are strict except a single retained-state
coordinate of a canceled sibling, where 400 actual-main observations demonstrate
both possible values. All 388 final observed after-states match main without
using that allowance. The store tests exercise actual selected-byte failures,
posting deduplication, sparse/dense boundaries, cancellation and closed readers.
They do not claim exact JVM work-callback or configured worker-budget parity.

The final full-module command was `go test -race ./...`: query ran for 52.516s,
while some unaffected packages used cached results. `go vet ./...` succeeded.
Exact commands, source hashes, logs and terminal status are in `tests/receipt.json`.

## Measurement boundaries

`run-final-latency.py` first requires the three final correctness comparisons and
unchanged frozen module source. It runs fresh main cold, native cold, native
startup-prepared and main startup-prepared serially. Every run executes the entire
ordered 1,267-case workload. Native input manifests include transitive Go build
sources and all embedded regex data. No tests, builds or profiling run concurrently
with a timed runtime. The runner builds the next native binary between runtimes.

This is one sample per case/runtime/state, not P95. Query timers include dispatch,
parsing, execution and materialization; loading, setup, HTTP and serialization
are excluded. Existing main metrics/resource sampling do not yet have full Go
parity. See `../native64-latency-pilot/README.md` for the complete inherited
protocol, initialization and OS page-cache limitations. The receipt field
`candidateInputsIncludeUncommittedTimingDriver` is inherited from that runner;
for this attempt the uncommitted changes are the COUNT implementation, and the
timing driver itself is unchanged. Frozen input hashes identify the actual code.

`analyze-latency.py` compares every fresh case with the preceding Go pilot,
including the largest increases outside COUNT, to detect displaced work. Sums
of single-invocation times are diagnostic totals, never latency percentiles.
Repeated per-case P95, the 10x requirement, formal warm replay, complete server
parity and required PR benchmark checks remain outstanding.

## Verified single-sample results and decision

All four processes are terminal with the original exit 1. Both paired verifiers
confirm every ordered case, 1,266 successful signatures, the sole original error
and zero timeouts. All 1,152 original graph files are unchanged per runtime.
Main has 554 frozen inputs and native has 1,008 transitive build/runtime inputs;
all hashes match after execution. Both native binaries have SHA-256
`b49ffae0ab98c09c453d726af334df4e9b1234496debbffa79b43391aafcdee4`.

| Case | Cold old Go ms | Cold candidate Go ms | Cold fresh main ms | Startup old Go ms | Startup candidate Go ms | Startup fresh main ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| filtered-count-zero | 11,055.763 | 0.904 | 1.777 | 11,150.093 | 0.857 | 1.648 |
| filtered-count-targeted | 1,958.953 | 85.955 | 20.436 | 1,926.774 | 82.943 | 23.094 |
| filtered-count-dense | 885.805 | 88.714 | 17.314 | 901.137 | 89.302 | 17.783 |
| filtered-distinct-count-zero | 2.191 | 1.105 | 0.920 | 2.187 | 1.096 | 0.716 |
| filtered-distinct-count-targeted | 3,567.049 | 143.485 | 28.157 | 3,567.600 | 144.298 | 30.090 |
| filtered-distinct-count-dense | 3,362.056 | 95.243 | 48.199 | 3,373.310 | 91.656 | 31.658 |

| State | Go slower than fresh main | Single-sample main/Go >= 10 | Sum of successful-case Go times, old → candidate |
| --- | ---: | ---: | ---: |
| cold | 1180/1,266 | 1/1,266 | 95.192s → 74.240s |
| startup-prepared | 1184/1,266 | 66/1,266 | 93.919s → 74.007s |

The largest later-case increases are `regex-or-zero`: 428.224ms cold and
311.468ms startup-prepared. All case deltas, including increases, are retained
in `latency/*-before-after.tsv`. No comparable 11-second increase appears later
in either measured invocation. This rules out that visible displacement in these
runs only; one observation does not establish causal attribution or stability.
Fresh main's own successful-case totals vary from 28.354s cold to 24.110s startup.

Keep the main-equivalent COUNT path: it removes a demonstrated implementation
difference, preserves the full real64 results/state contract and substantially
reduces all six COUNT cases versus the prior Go pilot. Most nonzero COUNT cases
remain slower than fresh main, as do most cases overall. This is not the requested
10x P95 outcome. Candidate CPU/allocations were not separately profiled; the
baseline 2,184,529,752 allocated bytes and zero GC collections describe only its
instrumented first COUNT interval, including profiler overhead.

`capture-archives.json` records five byte-exact gzip archives, including both
initial failed captures. The ignored raw copies remain locally; the comparator
supports raw captures or gzip fallback. The evidence manifest covers committed
artifacts and preserves initial failures rather than silently replacing them.
