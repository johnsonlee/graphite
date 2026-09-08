# Native leading raw-projection work accounting

This functional follow-up starts from Go
`5cf282278c132c2bc987d217a9453ccfafc32b44` and uses actual main
`4e328b0109e13c896b74004823fb049fcb19251a`. It contains no performance
measurements and does not establish full server fidelity or P95 acceptance.

The raw leading probe charges each inspected CallSite before reading its string
IDs through the existing 1,024-unit buffer. Its final flush runs on success,
incomplete-probe fallback and decoding failure. A complete probe publishes its
cached IDs before that flush; a later budget failure therefore preserves the
completed cache. A cache hit refreshes LRU and charges `max(matchedIDs, 1)` before
reading the requested projections. The shared disjunction compiler now removes
duplicate OR/IN filters in encounter order using complete predicate identity and
Java UTF-16 equality, matching main's cache-key inputs.

## Public oracle and lifecycle boundaries

The new test consumes both original target captures: 26 scenarios, 52 ordered
operations, 13 actual-main-written persisted variants and 208 fixture files.
Sources are independently opened stores: 24 scenarios use 40 sources and two
controls use 39. The 37 ordinary executions, 11 context replacements and four
public prelude executions compare full results and ordered columns/provenance,
simple error classes and nullable messages, all eight request diagnostics,
remaining work/cancellation and four mapped storage states at every operation
boundary. Eight compiler controls check complete filter identity, order and
original string payload. No JVM error-message variability is admitted here.

The first native check failed because the harness compared Go's final live-store
snapshot against main's post-close snapshot. All 52 operation subtests passed;
16 parent final comparisons failed on cache clearing. The corrected test really
closes every Go store, checks successful Close and the public observer's
`ErrStoreClosed`, and compares the still-observable request/prelude state after
Close. Both complete JVM final records, including private storage getters, remain
strictly compared to each other. No zero-valued Go post-close storage snapshot is
invented. Existing store tests independently assert cache clearing, rejection of
get/put after Close, LRU ownership and concurrent Close.

Three JVM storage event counters and JVM stacks/private post-close getters have
no corresponding compared public Go observer. They remain in the original
archives; the eight request diagnostics are a separate, fully compared set.

Both earlier prepared captures (24 scenarios / 48 operations each) are preserved.
Their six fallback cases enter persisted-index read accounting and remain an
unresolved native configuration. The no-sidecar target was explicitly produced
by removing 11 generated sidecars, with each removed byte hash retained. It must
not be presented as proof of the prepared path. See `README.md` and
`prepared-comparison.json` for the actual differences.

## Verification

The corrected focused checks, full-module `go test -race -count=1 ./...` and
`go vet ./...` pass, with all 2,913 recorded inputs unchanged. The failed first
check is retained under `checks/preliminary-v1`; the corrected run is under
`checks/evidence`. The original oracle verifier passes without launching a JVM.

| Complete real64 capture | Original cases | Matching graph-state observations |
| --- | ---: | ---: |
| cold | 1,267 | 162,304 |
| warm prewarm | 1,267 | 162,240 |
| startup-prepared | 1,267 | 162,304 |

All compared public outcomes and 486,848 graph-state observations agree with the
pinned main captures. The three runtimes run serially on independent audited
clones after module checks finish. All 1,152 original fixture files and original
case order remain unchanged. Case 821 retains its original IllegalStateException,
and every original all-success gate exits 1. Formal warm does not reach its
prepared measured invocation. The JVM fully qualified exception-class observer
remains unavailable in Go; public simple-class/message equality is not complete
observer parity or evidence of schedule invariance.

The existing complete 201-case adversarial matrix retains 166/181 public and
19/20 provider-control matches, with the same 16 differences and no new compared
differences from the preceding committed capture. The legacy adapter does not
pass the new ExecutionContext or compare its diagnostics. Its original wording
about an unavailable diagnostics API is retained as historical adapter output,
not a statement that Go has no current request diagnostics primitives.

`checks/source-verification.json` binds all 2,546 module files to final checks,
the frozen real64 source and the matrix source before its diagnostic helper.
The archives retain full original failures and verify copied/decompressed bytes.

## Remaining acceptance scope

Persisted/indexed/parallel work accounting, nonempty Method and relationship/path
work, source-selection diagnostics, shared server request integration and
resource sampling remain incomplete. Raw-cache get/put currently poll Go context
before touching LRU/publication; cancellation timing can therefore differ from
main's private cache methods. The ordinary budget cases here do not establish
that cancellation-history boundary. `next-persisted-accounting.md` records the
next loader work and unexecuted evidence designs.

The existing adversarial semantic differences, original real64 case 821 error,
formal warm preparation failure and required benchmark gates remain open.
Earlier one-sample timings do not describe this changed module. Full 1,267-case,
per-state P95 acceptance still requires at least 200 valid observations per
runtime/case/state and a main/Go P95 ratio of at least ten for every case.
