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

## 2026-09-07 — Attempt 2: filter single-node MATCH before retaining rows

Hypothesis: the generic matcher retains millions of intermediate maps and path
states before WHERE rejects them; filtering single-node candidates as they arrive
can remove that allocation while preserving the existing pipeline's results.

| Item | Evidence / status |
|---|---|
| Implementation base | `a7de0bec67ae4dbd8b1dde621b6d9ce7cecef8d0`; this compares two native implementations, not native against main |
| Main correctness oracle | Pinned `4e328b0109e13c896b74004823fb049fcb19251a`, complete frozen HTTP responses |
| Candidate identity | `native64-streaming-attempt2/final-identity.json`; binary SHA-256 `29f4f824268cfd8db2d3c989e6e2627d848a3d97c3f6b81066fbcb9204062374`; exact source patch and new scan file retained alongside it |
| Real fixture | All 64 `/tmp/pr113-exp037-fixture.nXn4fg` persisted shards; 19,431,891 nodes / 20,448,885 edges; all per-graph catalog counts checked before execution; 1,152-file frozen manifest in `native64-profile-a7de0bec/fixture-files.json` |
| Scope | One-node MATCH with WHERE, no relationship or named path; retain only accepted binding rows, preserve optional misses and provenance; no LIMIT, ORDER BY, aggregation or projection pushdown |
| Correctness | Three profiled full responses match; final isolated HTTP binary passes all 42/42 fixed query responses and checked headers on all 64 graphs; independent query/server race tests and query vet pass |
| Allocation | Wrapped zero-hit: 38,142,226,112 → 20,227,526,272 bytes (about −47%); raw four-property zero-hit: 32,796,831,280 → 14,854,469,648 bytes (about −55%) |
| CPU / heap | Wrapped request user+system CPU 82.136 → 38.447 s; raw 57.972 → 23.587 s. Request-end heap 36.50/31.32 → 9.16/9.58 GB; approximately 6.27 GB after forced GC. Request-end heap is not peak RSS |
| Diagnostic latency | Wrapped execution+marshal 34.495 → 25.212 s; raw 21.158 → 14.818 s. Single profiled observations with host co-tenancy, not P95 or controlled paired speedups |
| Control | Supplemental no-WHERE Method count stays on the original path: allocation 2,441,487,384 → 2,441,487,512 bytes; 0.683 → 0.752 s is one noisy observation, not a regression conclusion |
| Variant history | First variant also streamed no-WHERE patterns; its complete source/results remain separately named. Final variant narrows the guard to WHERE and reruns all three real shapes; old records are not overwritten |
| Decision | Keep the narrower streaming change for its verified allocation reduction and fixed-workload correctness. Overall functional parity and 10× P95 remain unproven |

Full raw profiles, runtime deltas, exact commands, source identities and complete
42-query responses are in `docs/go-server-baseline/native64-streaming-attempt2/`;
base profiles and source identities are in `native64-profile-a7de0bec/`. Later
function/Unicode feature changes are outside this isolated profile and require
subsequent full-corpus verification. Integration preserves their row-order
helpers; do not attribute these isolated timings to the complete later server.

## 2026-09-07 — Attempt 3: bounded deterministic response cache

Hypothesis: reuse successful deterministic query bodies across repeated requests,
with graph-generation, selection-order, mode and limit identity, while retaining
normal admission/cancellation and a bounded byte LRU.

| Item | Evidence / status |
|---|---|
| Base / candidate | Native `8fccf511`, isolated cache diff and complete file manifest under `native64-cache-attempt3/`; exact binary hash and command in `run/identity.json` |
| Real fixture | All 64 `/tmp/pr113-exp037-fixture.nXn4fg` graphs; all catalog counts checked; all 1,152 manifest files freshly hashed and verified |
| Correctness | Server race/vet and cache lifecycle tests passed. Independent review then reproduced nondeterministic inline-property error order in the existing query evaluator; caching could freeze the first success and suppress other errors |
| Counterexample | External tiny correctness fixture, identical query repeated: 181 successes / 19 division errors uncached; cache subsequently reused a success after one build. Exact overlay/source/log retained; no synthetic performance claims |
| Real replay | Intentionally stopped after 11/42 complete first-replay responses, all 11 matching main. Remaining 31 unverified; no warm samples collected |
| Latency | Raw single first-replay observations retained per completed query. No P95, paired comparison or speedup result |
| CPU / memory | GC trace retained; request allocation, paired CPU and peak RSS evidence incomplete. No improvement claim |
| Decision | Reject this frozen candidate. Cache production code was never integrated. Keep only the experiment/audit record; repair property evaluation order before a fresh numbered cache attempt |

This failure is not a reason to hide exceptions with caching. Main's ordered
property evaluation and source-visible error behavior must be established first.
`native64-cache-attempt3/README.md` explains the exact incomplete denominator,
commands, scope and intentional process shutdown. Later successful runs must not
replace this record.

## 2026-09-07 — Attempt 4: reuse decoded single-node WHERE candidates

Hypothesis: keep a borrowed decoded candidate while evaluating WHERE, avoiding
one large node/method interface allocation per rejected candidate. Freeze values
before any container, retained binding or output; retain existing decode and
error order. This does not change string algorithms or use an index/cache.

| Item | Evidence / status |
|---|---|
| Native base | `4124bfc44bedd7915e238b7a08f2524b48ed47b6`; includes later compatibility work omitted by Attempt 2's older profiles |
| Candidate identity | `native64-slot-attempt4/candidate-source-final.json`, complete 14-file patch SHA-256 `6084e090235c6f6505e17e05fafe657023ba148b1d70667be20454dd13d6955a`; profile binary `d13e93d94e755f5d8a3d3f7cc319969b6d4ecea8f19a81ff4d4ee705542de9da` |
| Real fixture | All 64 persisted shards at `/tmp/pr113-exp037-fixture.nXn4fg`; all catalog counts and 1,152 frozen file hashes rechecked before both profiles |
| Correctness | Three complete profiled responses match; 40 main JVM query cases, 198 independent boxed/candidate queries and 4,318 expression/error/lifetime comparisons pass; full-module race/vet pass |
| Allocation | Wrapped zero-hit: 55,093,306,872 → 42,656,840,592 bytes (about −23%); raw four-property zero-hit: 24,431,143,712 → 11,994,716,656 (about −51%) |
| CPU / heap | User+system CPU 103.565 → 93.482 s and 41.058 → 30.540 s; request-end heap 8.89 → 7.39 GB and 8.11 → 6.61 GB; post-forced-GC heap about 6.28 GB. These are not peak RSS measurements |
| Diagnostic latency | Wrapped execution+marshal 64.053 → 62.580 s; raw 23.378 → 21.677 s. Single profiled observations with host co-tenancy, not P95 or paired acceptance |
| Control | No-WHERE Method count stays on the original enumeration: +7 allocations / +1,040 bytes; 0.772 → 0.778 s is one noisy observation, not a regression conclusion |
| Variant history | Initial scratch-key deletion changed six whole-row string orders; final nil placeholder preserves original key position. Only the corrected source was profiled; failed audit evidence retained |
| HTTP limitation | Replay stopped after 3/42 completed responses: first query HTTP 504, next two HTTP 200 fully equal. A fresh unmodified native base also returns HTTP 504 for the first query. Remaining 39 have no completed result; no complete HTTP parity claim |
| Decision | Keep verified allocation reduction while the existing native timeout remains unresolved. Full 42-response parity, cold/warm P95 and the 10× goal remain outstanding; do not count the timeout as a faster result |

Raw profiles, source/binary identities, counters, output bodies and default
deadline diagnostics are in `docs/go-server-baseline/native64-slot-attempt4/`.
Independent before/after correctness audit is in `candidate-slot-readonly-audit/`.
No performance run used a synthetic fixture, no failed body was discarded, and
subsequent experiments must preserve this incomplete HTTP record.
