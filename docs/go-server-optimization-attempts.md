# Go server rewrite and optimization attempts

Acceptance remains complete native-Go server/Cypher fidelity and at least 10×
lower P95 than main on the complete 64-graph real corpus. Single-graph or
synthetic timings cannot establish acceptance. Independent optimization
hypotheses belong in separate commits and chronological entries here.

## 2026-09-07 — Attempt 1: native implementation and real-corpus baseline

Hypothesis: a native Go storage/query/server implementation can preserve the
existing server behavior and enable the required latency reduction. This entry
records the initial implementation and measurement setup, not an accepted
optimization or proof that language choice alone improves latency.

| Item | Evidence / status |
|---|---|
| Performance base | Remote main `4e328b0109e13c896b74004823fb049fcb19251a`, confirmed by `git ls-remote origin refs/heads/main`; isolated clone built successfully |
| Candidate | Initial native implementation based on `599a3ece`; exact frozen binary and 97-file source identities are recorded in `native-initial-snapshot/manifest.json` |
| Real fixture | `/tmp/pr113-exp037-fixture.nXn4fg`, all 64 persisted Android/Hive/Kotlin compiler/Tika shards; byte identities and provenance required by `scripts/real64-workload.json` |
| Catalog | 19,431,891 actual nodes; 20,448,885 edges; 1,374,983 methods; 5,046,935 call sites, independently read from running main64 |
| Workload | All 8 wrapped-discovery queries and 34 global-wide source shapes, root `/api/cypher`; parameter-to-HTTP literal adaptations explicitly recorded |
| Storage correctness | Complete ordered adjacency hashes for real Hive/Android agree with JVM (841,881 combined arcs); complete string identities agree; JVM-produced tiny format fixtures cover all node/edge kinds |
| Initial HTTP correctness | 40 main empty-catalog oracle cases captured; first 30 real-Tika differential cases pass, including 9 native Cypher queries; this is limited coverage, not full parity |
| Parser | Go code generated from repository ANTLR grammar, generated artifact reproducibility verified; no JVM execution backend |
| Lifecycle | Independent tests for atomic replacement/rollback, lease retirement, timeout admission, TCP half-close versus reset, and race detector |
| Latency | Main64 diagnostic c1: 8,400/8,400 correct requests; mixed P50 1.616 ms, P95 287.796 ms, P99 343.749 ms. Frozen candidate64 correctness preflight completed 42/42 full responses; no candidate P95 or 10× result |
| CPU / memory | Diagnostic benchmark collects process CPU/RSS. Current adjacency stores forward/reverse Go edge structures. Frozen candidate64 starts successfully with matching complete catalog. Startup GC reported about 5.8 GiB live heap; RSS around 16 GiB during early full-corpus correctness replay; these are diagnostic observations, not a performance acceptance result |
| GC / allocation | Not captured in first main diagnostic run; required for final paired evidence. Future paired launch protocol records JVM GC and Go GC/allocation evidence |
| Environment caveat | Initial main capture shares host with compilation/tests and extra arithmetic oracle requests; explicitly diagnostic, unsuitable as uncontended acceptance proof |
| Main concurrency correctness | Diagnostic c4 completed 8,400 attempts with 190 HTTP 400 failures (`Cannot grow a closed mapped CallSite string-index reservation`); mixed all-attempt P95 664.031 ms, P99 836.092 ms. All failures remain in the denominator; this is not a valid successful-response performance comparison |
| Decision | Continue implementation and verification. Neither feature parity nor the performance goal is accepted yet |

See `docs/go-server-baseline/` for raw observations, precise commands and artifact
identities. The native benchmark scripts refuse single-graph acceptance,
incorrect query results, incomplete fixtures and unsupported comparisons.

### Follow-up within the initial rewrite

The immutable initial Go binary and source manifest are saved under
`docs/go-server-baseline/native-initial-snapshot/`; the binary SHA-256 is
`1e6d7f769c5b9ca7180d92bab7713028afd9a27c03d53e72943cb81da51830b8`.
Both running 64-graph catalogs matched exactly. The complete fixed 42-query
preflight passed with HTTP 200, full response equality and no header differences;
see `native64-preflight/completion.json`. Its roughly 904.6-second total wall time
is only a correctness-run duration, not a repeated-query latency comparison. The
two dedicated runtimes were stopped after completion and their logs preserved.

A separate five-case provenance suite passed three cases and exposed two anonymous
MATCH differences in the frozen binary. Current source corrects both with
independent regression tests, but those fixes are not part of the frozen-binary
receipt. Do not report 47/47 parity or infer a P95 from this single replay.

Further initial changes add metadata-backed overview, endpoint extraction,
source-faithful semicolon/blank parsing and parsing cancellation. None changed
the frozen binary. Native relationship/path query execution, full
function/regex/value semantics, topology/C4 integration, OpenAPI/metrics and
the validated 10× P95 result remained outstanding at the first implementation
commit. Subsequent feature work must keep these receipts immutable.
