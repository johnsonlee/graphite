# Native index lifecycle prerequisites

The native store now exposes explicit lazy/startup-prepared opening, complete
CallSite index preparation, and an invocation-boundary clear operation. Clear
preserves graph/node ownership and its offset table, may persist a prepared
index first, drops retained/mapped indexes and query caches, and invalidates the
native cold candidate/trigram certificates. A first index loader must finish
before clear resets its publication. A caller waiting to clear can cancel.

`ClearStringPropertyIndexes` is an internal benchmark-owner operation. Call it
between joined query executions after releasing their index handles. It does
not implement concurrent query reconfiguration. It is not used to close/reopen
the graph or replace the graph registry. `OpenModeWithOptions` keeps existing
lazy opening as the default and prepares/persists before returning only when
the new option is supplied. The server's default loading behavior is unchanged.

## Main comparison

The prior actual-main lifecycle capture supplies 10 scenarios and 70 operations
(30 queries, 30 clears, 10 preparations). All 30 complete public responses/errors
match, including startup-success/after-clear-error behavior for the bad-node
fixtures. There are 150 before/after/load state comparisons: retained index,
mapped view, trigram readiness, persistence origin, mapped validation count,
and full index-file length/SHA256. All these observations match main exactly.
The generated/repaired indexes therefore also match main's bytes.

Two main raw-cache counters (`rawMatchCount`, `rawProjectionCount`) have no native
counterpart in this observer. The comparison explicitly requires them to be
zero in this corpus and does not fabricate native values or claim their parity.
`candidate-final.json` retains exactly what Go observes; `verify.py` states the
comparison's field set and rejects extra/missing fields. Full real64 state and
consumption validation remains necessary.

Two additional actual-main-generated boundary fixtures cover no CallSites and
CallSite strings shorter than three UTF-16 units. Main returns preparation=false
for both. The short-string case retains structural postings but no ready
trigrams, and clear does not persist an index. The first candidate incorrectly
reported readiness and wrote an empty-trigram index; `boundary-initial.log` and
the two initial source snapshots preserve that failure. The corrected code
uses the existing trigram-availability test and leaves the original persistence
guard unchanged. Both complete boundary observations now pass.

Native tests additionally verify graph readability after repeated clears,
all three projection cache classes absent after rebuilding, fresh cold
certificates, mapped-range cache reset, cancellation without file/state changes,
clear waiting for a real paused first loader, and closed-store handling.
Targeted and full-module race tests pass, as does vet. No synthetic timing,
allocation or speedup result is used here.

## Evidence and remaining work

`candidate-initial.json` is the first 10-scenario capture; it passed those cases
but did not cover the later short-string boundary. `candidate-final.json` is
the corrected capture. `boundary-main.json`, `IndexBoundaryOracle.java`, command
arrays, class file and generated fixtures preserve the actual main controls.
Its helper class is the committed `native-index-lifecycle/IndexLifecycleOracle`.
`verification.json` reports the independent public/state/file comparison.

Re-run `python3 docs/go-server-baseline/native-index-lifecycle-implementation/verify.py`
from the repository root. Run `go test -race ./...` and `go vet ./...` from
`graphite-server`. The immutable case definitions and request-scope options
already exist; the next step is the full 1,267-case driver with matched
cold/warm/startup-prepared setup and actual 64-graph execution. This change does
not run that workload, prove all source-access behavior, or measure P95.
