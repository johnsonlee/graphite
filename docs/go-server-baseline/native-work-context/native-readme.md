# Native request work context and bounded execution

Baseline Go is `2547ef3d8c2c003ec8b66d3d00b7039e4a0bdd1e`; actual main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a functional follow-up to
close execution-condition gaps. No latency or allocation measurement is made.

`ExecutionContext` now owns one atomic request tracker and cancellation signal.
Sequential executions and UNION branches share that tracker; a budget-only call
creates a fresh tracker. Exact exhaustion succeeds, excess consumption saturates
the remaining budget at zero before throwing, and zero consumption is allowed
after exhaustion. Negative consumption is rejected before cancellation. The
first supplied cancellation object is retained without mutation; its identity
and Go cancellation classification remain observable. A one-way context bridge
interrupts storage and workers, while local early-stop and completed-call cleanup
do not cancel the reusable request signal.

Ordinary mapped scans charge after successful decoding and skipping missing
offsets, matching the underlying main iterator's `hasNext` before tracked `next`.
Literal element-ID seeks charge before lookup, including valid absent IDs.
Graph-free expressions, UNWIND and cancellation polls do not consume graph work.
General fallback diagnostics accumulate even when execution later fails.

The explicit `ExecuteWithMaxRows` and cross-graph equivalent preserve constructor,
bound-validation, parsing and cancellation order. They append or tighten the
last literal LIMIT, or insert a separate bound before a nonliteral LIMIT.
UNION ALL stops at its remaining row bound; UNION DISTINCT still evaluates later
bounded branches for retained provenance. Zero bounds retain main's observable
column-discovery and scan behavior. The existing native `limit` argument keeps
its legacy post-execution truncation contract; server callers have not yet been
migrated to the new explicit bounded API.

The ordered-property route now uses main's admission conditions and a stable
bounded heap, including the 10,000-row admission boundary. This makes its graph
consumption and successful-fast-path diagnostic correspond to the actual route.
It does not label a generic sort as a completed fast path. The existing node-only
streaming pagination route also records completion after its iterator/ranking
succeeds, matching `tryStreamingFilteredMatchLimit`.

| Actual JVM oracle | Scenarios | Ordered operations |
| --- | ---: | ---: |
| Context, tracker, cancellation and sequential execution | 51 | 129 |
| Literal element-ID seek and decode ordering | 14 | 21 |
| Explicit maxRows and UNION | 19 | 19 |
| Ordered-property admission, results and work | 25 | 25 |
| Constructor / bounds / parsing / cancellation priority | 10 | Separate constructor/execution observations |

All 119 scenarios pass against the actual JVM records. Tests compare complete
ordered public results and atomic failures, exact simple exception classes and
nullable messages, all eight cumulative diagnostics, remaining budget and
cancellation reason/state. They do not fabricate JVM stack frames, fully qualified
class observations or subtype getters that Go does not expose. The 51-case main
oracle retains the differing individual worker shares from two captures; both
have exactly 100 consumed units and four budget-exceeded workers. All other
scenario records repeat exactly. The new seek, bounded, ordered and constructor
supplements each have two fully equal independent JVM captures.

Full module race tests and vet pass with 2,583 recorded inputs unchanged. The
Go-specific cancellation bridge tests also verify interrupted storage and a
successful next call after local cancellation. An independent read-only review
found no blocking issue in the new tracker, binding, bounded execution or ordered
route. `checks/preliminary` retains the initial missing streaming-fast diagnostic
failure and a test selector that initially covered only the original 51 scenarios;
the final frozen check run covers every declared scenario.

## Remaining integration and acceptance

This does not establish complete finite-budget enforcement. Raw/indexed string
storage consumers, their buffered work batches, several optimized planner
counters, relationship/path work and request-wide server integration remain
unfinished. All eight tracker primitives exist, but not every execution route
has its corresponding producer. The deprecated CLI work-budget flag remains
unchanged. Equivalent resource sampling, full server fidelity, required benchmark
gates, formal warm setup and the 10x per-case P95 objective remain open.

All three real64 captures execute serially after the module checks and JVM
captures finish, on separate audited clones of the same 64 graphs. Every runtime
preserves all 1,152 original files and the complete original case order.

| Real64 state | Cases | Matching graph-state observations |
| --- | ---: | ---: |
| cold | 1,267 | 162,304 |
| warm prewarm | 1,267 | 162,240 |
| startup-prepared | 1,267 | 162,304 |

All compared public outcomes and graph-state fields agree, totaling 486,848
observations. The original case821 failure remains and each original all-success
gate still exits 1. Warm never reaches a prepared formal measurement invocation.
The observer-only fully qualified exception-class difference and historical
contended cache-state anomaly remain documented; this does not prove complete
diagnostic parity or schedule-invariant speculative publication.

The complete existing 201-case adversarial oracle has no new compared result,
error or state differences. It still matches 166/181 public cases and 19/20
scoped provider controls, retaining all 16 mismatches and comparison exit 1.
The legacy adapter does not supply the new execution context or compare its
diagnostics. Its old wording about a missing diagnostics API is retained in the
raw archive, with this qualification; the presence of primitives does not mean
all routes now produce equivalent accounting. The initial matrix preparation
failed because PATH selected a `cp` without the clone option, and the dependent
helper copy found no module. No query ran in that attempt. Its files are retained;
the successful capture uses `/bin/cp` and new source/output directories.

No prior n=1 timings are attributed to this changed engine. Complete outputs,
source identity checks, original failures and byte-verified compressed archives
are retained under `checks/` and `real64/`.
