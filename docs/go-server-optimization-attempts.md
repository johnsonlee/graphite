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


## 2026-09-07 — Attempt 5: match well-formed strings without UTF-16 arrays

Hypothesis: valid UTF-8 operands have the same complete-code-point substring,
prefix and suffix matches as their Java UTF-16 representations; using byte
matching for those operands avoids transient arrays. Keep the UTF-16 fallback
for malformed/WTF-8 operands and preserve evaluation/null/type/error behavior.

| Item | Evidence / status |
|---|---|
| Native base | Frozen Attempt 4 candidate: `4124bfc4` plus patch `6084e090235c6f6505e17e05fafe657023ba148b1d70667be20454dd13d6955a`, subsequently integrated as `f8541882`; native-to-native diagnostic, not main latency comparison |
| Candidate identity | Five-file delta `6f3b553bc33ff58d5fc7701156a49406e61fab070c297efd9dbe27a6698f973d`; only `internal/query/eval.go` changes production; profile binary `3f45fb79dea5b40f331a96bb784e2e6975341be7320d764ba950c977e8110a5b`; separate HTTP binary identity retained |
| Real fixture | All 64 `/tmp/pr113-exp037-fixture.nXn4fg` shards, 19,431,891 nodes / 20,448,885 edges; all 1,152 frozen files freshly hash-verified and all per-graph catalog counts checked |
| Correctness | 252 main JVM queries × 3 repetitions, 3,456 definition comparisons; independent 24,576 definition / 60 operand-order / 12 cancellation-boundary cases; independent root archive full-module race/vet passes |
| HTTP | 42/42 HTTP 200, complete ordered response equality and no checked header differences against pinned main; default timeout, sequential full-corpus replay. The prior first-query timeout is resolved in this replay |
| Allocation | Wrapped zero-hit 42,656,840,592 → 20,063,190,576 bytes (about −53%); raw four-property zero-hit 11,994,716,656 → 3,661,645,640 (about −69%) |
| CPU / GC | User+system CPU 93.482 → 57.650 s and 30.540 → 13.520 s; request GC cycles 7 → 3 and 2 → 0 |
| Heap limitation | Request-end heap rises 7.39 → 9.18 GB and 6.61 → 9.95 GB with fewer collections; post-forced-GC heap remains about 6.28 GB. No reduced retained-heap or peak-RSS claim |
| Diagnostic latency | Wrapped execution+marshal 62.580 → 44.367 s; raw 21.677 → 13.532 s. Single instrumented observations with co-tenancy, not P95 or controlled paired acceptance |
| Control | Supplemental no-WHERE Method count allocation +384 bytes, wall 0.778 → 0.765 s; one noisy observation does not establish a regression or improvement |
| Cancellation boundary | Checks bracket the valid-UTF-8 fast path; the standard-library operation is not internally cancellable. No maximum cancellation latency claim |
| Decision | Keep verified allocation reduction and fixed-workload HTTP parity. Overall functional parity, cold/warm repeated P95 and the 10× main-relative goal remain outstanding |

Raw source identities, full responses, profiles, exact commands and derived
counter comparisons are in `docs/go-server-baseline/native64-string-predicate-attempt5/`;
the independent review is in `string-predicate-readonly-audit/`. Attempt 4's
incomplete/504 receipt is preserved. No performance measurement uses synthetic
data or drops a failed response from its denominator.


## 2026-09-07 — Attempt 6: certified CallSite string candidates

Hypothesis: filter the existing four-property CSR dictionaries before decoding
nodes, while proving index membership and preserving original native scan
failures, candidate order and downstream evaluation. No trigram/signature filter
or query-response cache is included in this attempt.

| Item | Evidence / status |
|---|---|
| Native base | `1c235916a930a9dd65ce2a68dfaee854a40e9d05`, including the optional index reader; native-to-native diagnostic, not main latency comparison |
| Candidate identity | Frozen 127-file patch `22429f2b01531350bfd13c57eefb52c962a62e674353260d819a492dafb18e1e`; six production files; profile binary `18c3158ece6578fe4b793bbf7af750a1245b0a85920d2a207ac4e75dee315075` |
| Real fixture | All 64 `/tmp/pr113-exp037-fixture.nXn4fg` persisted shards, 19,431,891 nodes / 20,448,885 edges; all 1,152 frozen files freshly hashed before each profile; all catalog counts checked |
| Eligibility | Initial mandatory named single-node MATCH; four caller/callee string fields; whole pure OR of exact supported predicates/wrappers; MAPPED only; every selected graph must be certified before emission; Annotation/null/unsupported cases fall back |
| Correctness | 192 valid complete main queries and forced-scan comparisons; independent 1,456 admitted forms / 21,840 kind-and-transform comparisons; final integrated clean archive full-module race/vet passes |
| HTTP | 42/42 HTTP 200, full ordered-body equality and no checked header differences against pinned main; default timeout/admission, sequential all-64 replay |
| Corrupt-store limitation | 80/80 enabled-versus-forced-native-scan equal; 61/80 still differ from main (60 malformed-core/raw-projection/error cases plus one scoped metadata case). Existing main parity gaps remain explicit |
| Cold wrapped query | Allocation 20,063,190,048 → 3,576,489,880 bytes; GC 3 → 0; CPU 57.907 → 10.629 s; execution+marshal 44.656 → 10.612 s. Includes certificate and optional-index preparation |
| Warm identical repeat | Allocation 20,061,635,120 → 990,091,056 bytes; GC 3 → 0; CPU 57.816 → 1.282 s; execution+marshal 44.479 → 1.279 s; proof retained in same process, no result cache |
| Warm raw four-property zero | Allocation 3,661,675,032 → 111,622,880 bytes; GC 0 → 0; CPU 13.540 → 0.165 s; execution+marshal 13.542 → 0.163 s |
| Heap / control | Cold wrapped request-end heap rises 8.90 → 9.86 GB; warm wrapped 8.28 → 7.27 GB, raw 9.95 → 6.40 GB; post-forced-GC heap about 6.28 GB, not peak RSS. Method control +96 bytes, 0.766 → 0.777 s is not a meaningful regression claim |
| Test finding | Initial integration test assumed fixed sort check count; independent 40-run diagnosis showed cancelAt 118/119 was not reached when only 117/118 checks occurred. Test now distinguishes reached thresholds; 40 repeats and full race/vet pass. Failed receipts remain; no production change from this correction |
| Integration | Root `3feb4157` plus the candidate preserves later count/early-limit changes in engine.go; one cancellation test corrected, other 125 frozen files unchanged. Source hashes and independent final archive recorded separately |
| Decision | Keep verified allocation reduction and fixed-workload HTTP correctness. Single profiled cold/warm observations include host co-tenancy and are not P95, controlled paired acceptance or proof of the overall 10× main-relative target |

Exact source/binary identities, all outputs, profiles, counters, initial failed
test diagnosis, final verification and reproduction commands are retained in
`docs/go-server-baseline/native64-callsite-candidates-attempt6/`. No performance
measurement uses synthetic graphs. Forced GC outside requests is excluded from
request counters; no failed request is omitted or treated as a fast success.


## 2026-09-07 — Attempt 7: certified shortest trigram anchors

Hypothesis: replace full property-directory scans with the shortest necessary
trigram span for eligible CONTAINS arms, retaining exact matching. A cold proof
of derived-index completeness is part of this same hypothesis and measurement.
No signature, lowercase cache, response cache or prefix/suffix path is added.

| Item | Evidence / status |
|---|---|
| Native base | `4182478ee0575c3a359a47a152ed99edb4a4ede7` (Attempt 6 integrated), not main latency baseline |
| Candidate identity | 69-file patch `e3e6c413d129adb67097d122c8e883bbe6273996f5f02aa75450f21098341ed4`; four production files; profile binary `4c6de7c06fff960e765fe1d833ecbdbfc0d9f8c7745c5a051a4c9609d3cf96d7` |
| Real fixture | All 64 `/tmp/pr113-exp037-fixture.nXn4fg` shards; all 1,152 file hashes and all catalog counts reverified for each process |
| Correctness | 296 main observations × three native paths × clean/missing/extra sidecars = 2,664 complete comparisons; independent 292 raw + 336 LOWER necessary-substring anchor checks; independent frozen and integrated archives pass full-module race/vet |
| HTTP | 42/42 HTTP 200, complete ordered-body equality and no checked header differences against pinned main; default deadline/capacity, sequential all-64 requests |
| Cold regression | Wrapped allocation 3,576,499,936 → 4,636,974,256 bytes; CPU 10.531 → 15.019 s; execution+marshal 10.532 → 15.012 s. Additional proof work is inside the measured query |
| Warm wrapped repeat | Allocation 990,094,088 → 1,022,744 bytes; CPU 1.285419 → 0.004735 s; execution+marshal 1.283561 → 0.002235 s; no query-result cache |
| Warm raw four-property zero | Allocation 111,623,440 → 739,272 bytes; CPU 0.163579 → 0.002629 s; execution+marshal 0.161139 → 0.001273 s |
| GC / heap / control | All measured requests have zero GC cycles. Cold request-end heap 9.86 → 10.92 GB; post-forced-GC heap remains about 6.28 GB, not peak RSS. Method control -1,968 bytes and one small timing difference are inconclusive |
| Remaining costs | Single candidate HTTP fetches still take 18.98 s for bimodal prefix, 12.42 s for wrapped DISTINCT dense, and 10.42 s for broad all-64 distribution; no P95 or main-relative conclusion from those single observations |
| Integration | Root bbdfae2a retains its CandidateNode failNodeRead mapping; other 68 frozen files identical. Final clean archive and external anchor proof pass |
| Decision | Keep verified warm allocation reduction with explicit cold regression and fixed-workload HTTP correctness. Overall functional parity and the 10× repeated paired main-relative P95 goal remain open |

Raw evidence and reproduction are in
`docs/go-server-baseline/native64-trigram-anchor-attempt7/`. Timings are individual
instrumented observations with recorded host co-tenancy; very short warm CPU
profiles have too few samples for fine hotspot attribution. Synthetic data is
used only for correctness. Previous malformed-core/raw-projection gaps remain
separate and no failed response is omitted from the denominator.


## 2026-09-07 — Attempt 8: ASCII ROOT lowercase without code-point arrays

Hypothesis: entirely ASCII lowercase inputs can preserve Java ROOT output and
per-code-point cancellation callbacks without allocating UTF16/CodePoints arrays.
Non-ASCII inputs and uppercase conversion keep the existing path; no case cache,
query cache or raw projection change is included.

| Item | Evidence / status |
|---|---|
| Native base | de0608f075174833a06f31eb3214b3ba9c4e0917; same corrected response-serialization diagnostic harness on both sides, not main latency baseline |
| Candidate identity | One production file; patch 2101021c97cf94f96372c4d4956fec5e55fc4534e1aaaf4ee5553a8986a726d7; profile binary 682e0c92d9cac0ec10b4af9a8e61587f6a28806dad20e996f4f8198672989b6f |
| Real fixture | All64 persisted shards, all 1,152 frozen hashes and all catalog counts freshly checked before each profile |
| Correctness | 16,384 actual Java17 pair oracles; independent 20,544 old-Case output/callback controls and 110 cancellation positions; candidate and integrated full-module race/vet pass |
| HTTP | Independent clean command: 42/42 HTTP 200, complete ordered body and checked header equality against pinned main, default 60-second deadline |
| Prefix first / repeat | Allocation 18,222,452,888→11,136,246,944 and 15,636,032,040→8,549,794,336 bytes; GC 2→1 both; CPU 42.501→28.274 and 31.735→18.506 s |
| Dense DISTINCT first / repeat | Allocation 12,287,401,632→7,807,821,560 and 10,237,933,488→6,406,013,408 bytes; GC 1→1; CPU 23.870→19.579 and 18.454→15.074 s |
| Ordinary dense | Allocation 8,070,837,576→4,238,998,704 bytes; GC 1→0; CPU 16.206→6.057 s |
| Diagnostic latency | Prefix first 32.315→22.931 s, repeat 19.269→11.946; DISTINCT first 18.663→13.424, repeat 12.867→8.461; ordinary 10.715→5.985. Single instrumented co-tenant observations, not P95 |
| Heap limitation | Ordinary request-end heap 10.07→10.52 GB despite less allocation; post-forced-GC about 6.284 GB. No retained-heap reduction or peak-RSS claim |
| Integration | Exact candidate production/test/oracle files onto f4873238; full module race/vet pass. Independent HTTP executable excludes diagnostic-only serializer export |
| Decision | Keep verified allocation reduction and fixed-workload parity. Full functional compatibility and repeated paired main-relative 10× P95 remain unproven |

Evidence and commands are in `docs/go-server-baseline/native64-ascii-lower-attempt8/`.
The baseline diagnostic and its failed raw-engine-serialization harness are
preserved in `native64-positive-profile-de0608f0/`; the corrected harness calls
the actual server serializer. All comparison outputs preserve complete arrays,
values and provenance. No failed run is discarded or treated as a fast success.


### 2026-09-07 follow-up: indexed DISTINCT compatibility diagnosis

The separate functional integration `f9dc0aac` reproduces main's selective
projection semantics; it was not accepted as a performance optimization. A
fresh all64 five-query diagnostic found dense DISTINCT allocation reductions
alongside a major execution/CPU regression. CPU samples were inconsistent with
both the wall interval and process CPU counters, so a second control disabled
CPU sampling instead of treating sample percentages as a proven cause.

| Item | Evidence / status |
|---|---|
| Exact control revisions | `bb961cd8` before indexed DISTINCT; `f9dc0aac` after; two sequential fresh processes |
| Fixture / correctness | All64, all 1,152 hashes and catalog totals freshly checked per process; five complete bodies equal to pinned main and each other |
| Dense first, no CPU sampler | Allocation 7,807,915,504→5,770,519,952 bytes; execution+marshal 13.585→59.740 s; process CPU 19.674→296.275 s |
| Dense repeat, no CPU sampler | Allocation 6,405,969,320→334,421,632 bytes; execution+marshal 8.427→53.414 s; process CPU 15.062→250.703 s |
| Other paths | Prefix allocation/time approximately unchanged. Subsequent ordinary allocation/time rises with different prior Store/index history; not an isolated ordinary code comparison |
| Decision / next work | Preserve compatibility and record the unresolved performance regression. Audit repeated target-SID scans and cancellation contention before testing a separate optimization hypothesis |
| Limits | Single instrumented observations with background correctness/build work, not HTTP/P95, peak RSS, or main-relative acceptance |

Raw sampled and non-CPU-sampled controls are respectively in
`docs/go-server-baseline/native64-distinct-profile-f9dc0aac/` and
`docs/go-server-baseline/native64-distinct-no-cpu-profile/`. Neither supports
completion of the requested 10x main-relative P95 goal.


## 2026-09-07 — Attempt 9: lazy selected-string SID cursor (rejected before measurement)

Hypothesis: replace repeated target/property full-table searches with one lazy
per-source cursor, retaining only wanted strings and preserving first-SID lookup.
The candidate changed two lookup sites plus a helper, but no production changes
from this attempt were integrated.

| Item | Evidence / status |
|---|---|
| Base / candidate | `eebec091`; isolated `/tmp/graphite-go-selected-sid-eebec091`; rejected source/patch and hashes retained |
| Real fixture motivating work | All64 `/tmp/pr113-exp037-fixture.nXn4fg`; prior `bb961cd8`→`f9dc0aac` diagnostic preserved separately |
| Correctness | Six actual-helper race tests; independent whole-module race/vet; all17 original oracle JSON files unchanged, preserving739/1048 overall matches |
| New main evidence | Actual pinned-main loaded StringTable.findId returns midpoint SID1 for[a,a,a] and misses a in[b,a]; current Go first-match and the lazy candidate differ |
| Latency / CPU / memory | Not measured; no synthetic performance substitute and no new64 run |
| Decision | Reject before performance testing. A direct port of main's loaded-table UTF16 binary search can remove the scan and resolve these accepted-table lookup differences. It is a separate hypothesis |

`docs/go-server-baseline/selected-sid-lazy-attempt9/` preserves the design,
actual main tables/results, unintegrated candidate and completed independent
checks. The initial design remains explicitly about the superseded first-SID
contract; it is not relabelled as evidence for the binary-search replacement.


## 2026-09-07 — Attempt 10: main loaded-table SID binary search

Hypothesis: replace the two selected-projection linear SID scans at their
original callsites with main's UTF16 binary search. This also corrects accepted
serialized duplicate/unsorted-table lookup behavior. No cache, table sorting,
new candidate set, task scheduling or Postings reordering is included.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `eebec091`; candidate adds two production files, separately verified tests and fixtures; exact patch/source/binary identities per run |
| Fixture | All64 `/tmp/pr113-exp037-fixture.nXn4fg`; all1,152 files/10,338,207,518bytes and all catalogs reverified per process |
| Correctness | 45 actual-main method lookups; two complete nine-source main queries fix previously extra g8 provenance; candidate and portable root integration full-module race/vet pass; all17 original oracle files unchanged (739/1048 overall,309 existing differences) |
| HTTP | Independent clean command42/42 HTTP200, complete main bodies/checked headers, all64catalog, default60-second timeout; stopped afterward |
| Dense first | Execution+marshal53.949→8.570s; process CPU260.150→43.156s; allocation5,770,530,048→5,804,032,136bytes |
| Dense repeat | Execution+marshal54.051→8.770s; process CPU258.078→40.931s; allocation334,408,336→367,914,936bytes |
| Allocation tradeoff | About33.5MB additional allocation per dense request for UTF16 comparison; not an allocation/GC improvement |
| Controls | Prefix and ordinary do not use the helper; their small single-run timing changes are inconclusive. Complete five-query ordered outputs equal main and each other |
| Decision | Keep functional correction and verified dense CPU/latency improvement over the regressed native base. Overall main-relative10x P95 and100% parity remain unproven |
| Measurement limits | CPU sampling disabled; process CPU from getrusage. Single instrumented observations with background correctness/build work, not HTTP/P95 or peak RSS; one64 process at a time |

`docs/go-server-baseline/native64-findid-attempt10/` retains all commands,
identities, independent checks, complete responses and raw counters. The rejected
lazy first-SID hypothesis remains separately preserved as Attempt9.


## 2026-09-07 — Attempt 11: bounded generic DISTINCT retention (rejected)

Hypothesis: retain only selected direct-property/literal DISTINCT rows after the
indexed path declines, using a pull/ack candidate cursor to preserve decoding,
source consumption, provenance, error and cancellation behavior.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `87aaf0ad`; provider patch `0958287aa882135260f018b217577e3ca4c853cba444e66f0faf9bbd882c3226` plus independent Java-equivalent UTF16 string equality correction; production never integrated |
| Fixture | All64 `/tmp/pr113-exp037-fixture.nXn4fg`; all1,152 files/10,338,207,518bytes and catalog freshly verified per sequential process |
| Correctness | Provider and root whole-module race/vet; all18 emitted oracle files reproduced; original1,048 cases741 equal/307 existing differences; five diagnostic full responses equal pinned main and each other |
| Prefix first | Execution+marshal20.189→25.136s; process CPU25.819→141.585s; allocation11,136,134,320→3,869,456,152bytes |
| Prefix repeat | Execution+marshal11.397→25.175s; process CPU18.144→135.718s; allocation8,549,773,440→1,283,091,992bytes |
| Controls | Indexed dense first8.631→8.797s, repeat8.799→9.011s; ordinary11.121→11.144s, allocations effectively unchanged. Single-run variations do not establish control-path regressions |
| Decision | Reject despite allocation reduction: repeated target query takes2.21× elapsed time and7.48× CPU. No rejected production change remains in root |
| Next hypothesis / limits | Audit per-node pull/ack scheduling without claiming proven cause. No CPU sampler; instrumented co-tenant single observations, not HTTP/P95, peak RSS, or main-relative acceptance |

`docs/go-server-baseline/native64-generic-distinct-attempt11/` preserves the
provider freeze, independent equality failure/fix, both collector failures,
complete responses, source identities, counters, commands and rejection.
No shipping HTTP replay was spent on the rejected candidate. Full compatibility
and the requested repeated main-relative10x P95 result remain unproven.


## 2026-09-07 — Attempt 12: current-context cancellation checkpoint

Hypothesis: check the current evaluator context's Done channel without blocking,
retaining the original Err panic after closure, to avoid live cancelCtx mutex
reads. No cached channel, new context wrapper, checkpoint relocation, Store/regex
change, source ordering, task scheduling, or Close/join change is included.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `edefd884` (production identical to `87aaf0ad`); one production hunk in eval.go; provider patch `734d415a8b13f638f7941b6e7bb1d09e33d9bdc4a85c6e864c5bdbd26963951c` plus independent standard-context tests |
| Fixture | All64 `/tmp/pr113-exp037-fixture.nXn4fg`; all1,152 files/10,338,207,518bytes and complete catalog freshly verified in each sequential process |
| Correctness | Provider/independent/root race and vet checks; all17 complete original oracle files unchanged (739/1048 match,309 existing differences); seven actual evaluator/component tests and two independent external-cancellation/rebinding tests |
| Test migration | Eight existing tests use valid WithCancel-backed observers instead of Err-only/nil-Done injection; original failure assertions and query/Store/regex checkpoints retained and independently audited |
| HTTP | Independent actual shipping command42/42 HTTP200, complete typed main bodies/protocol headers/all64 catalog equal, default60s/capacity4, no profiling helper; server exited |
| Dense first / repeat | Execution+marshal10.815→4.075s and9.558→4.094s; process CPU55.847→22.092s and45.110→18.961s |
| Other paths / tradeoff | Prefix first20.290→21.873s, repeat12.033→12.735s; ordinary11.096→11.852s. Their CPU also increases; not labelled noise or a uniform speedup |
| Allocation / GC | Effectively unchanged allocation and identical per-request collection counts; this is not a GC optimization |
| Decision | Keep dense CPU/latency improvement with explicit unresolved other-path tradeoff. Full compatibility and main-relative10x P95 remain unproven |
| Limits | Current-context rebinding/cancellation error semantics tested, not identical concurrent scheduling instants. Single instrumented co-tenant observations, CPU sampler off, not HTTP/P95 or peak RSS |

`docs/go-server-baseline/native64-context-check-attempt12/` preserves commands,
provider and independent freezes, full response/counter evidence, actual shipping
HTTP verification and final integrated identities. The rejected generic cursor
and pending ordinary projection/cursor optimizations are not bundled here.


## 2026-09-07 — Attempt 13: mapped Node scalar cursor

Hypothesis: decode mapped Node primitive fields through a checked synchronous
slice cursor, eliminating bytes.Reader and temporary scalar buffers while
retaining complete decoding, field/error order, owning results and lifetime locks.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `df8d2b40`; provider patch `03c32896f4adbb409f4a86a5cfa9909acf038e29d05582f8d5568490fb456959`; exact two production files plus one test integrated, Attempt12 unchanged |
| Fixture | All64 `/tmp/pr113-exp037-fixture.nXn4fg`; all1,152 files/10,338,207,518bytes and complete catalog freshly verified per sequential process |
| Correctness | All16 JVM Node kinds/431 byte-prefix comparisons plus versions, malformed counts/SIDs/tags, IEEE bits, full consumption and Close ownership; independent combined module race/vet and all17 original oracle files unchanged (739/1048 match,309 existing differences) |
| HTTP | Independent actual shipping42/42 HTTP200, complete typed main bodies/headers/catalog equal; default60s/capacity4, no profile helper; process exited and port released |
| Prefix first | Execution+marshal21.474→18.449s; process CPU27.125→24.839s; allocation11,136,059,528→9,102,986,400bytes |
| Prefix repeat | Execution+marshal12.683→12.012s; process CPU19.387→18.719s; allocation8,549,709,704→8,320,020,376bytes |
| Ordinary dense | Execution+marshal11.847→11.547s; process CPU11.827→11.536s; allocation5,640,808,432→5,474,290,592bytes |
| Raw DISTINCT controls | First4.047→4.133s and repeat4.079→4.051s; effectively unchanged allocation. Single-run variation is not a uniform improvement claim |
| Decision | Keep full-Node allocation reduction and verified fixed-workload parity. No retained-heap/peak-RSS or main-relative10x P95 claim |
| Limits | GC counts unchanged; CPU sampler off, getrusage counters, background correctness/build work; individual instrumented observations, not HTTP/P95 |

`docs/go-server-baseline/native64-mapped-cursor-attempt13/` preserves exact
sources, commands, provider/independent/integration checks, counters and full
responses. The shipping verifier's initial temporary post-exit port-binding
failure is preserved; its unchanged final verification passed. Ordinary projection
and the replacement generic DISTINCT iterator remain separate work.
