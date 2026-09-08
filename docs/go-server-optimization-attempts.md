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


### 2026-09-07 follow-up: ordinary projection compatibility integration

This functional change reproduces main's ordinary raw projection, source
consumption, cache history and real Close-time sidecar handoff. It is not a
separate transport/decoder optimization; the paired diagnostic checks its
performance impact after the exact combined compatibility checks.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `2e2d8b52`; ordinary independent patch `78c0952ffece1eea29d96324c5d0a3e6d5dd2509bc0bac8b328e3d40e74b1792`, combined with retained Attempt12/13 changes |
| Functional coverage | Original1048 improves739→966 full main matches; all580 original DISTINCT and122 ordinary eligible match. Remaining82 preserved (58 main-success/native-error,20 differing errors,4 differing results) |
| Independent correction | Java-equivalent UTF16 predicate terms share ordinary cache keys; eight actual-main history steps preserve lone surrogates, aliases, duplicate fields and exact cache bytes |
| Integration checks | Full-module race/vet; all2095 files unchanged and equal root.33/34 whole oracle artifacts equal; rolling48 full responses equal,24 speculative mappedView state booleans differ and remain recorded |
| Persistence | Two native-generated sidecars read by actual main, four subsequent full responses equal. Real Close remains enabled in64 runs; original and isolated COW fixture hashes unchanged afterward |
| HTTP | Independent actual shipping42/42 HTTP200, complete typed main bodies/headers/all64catalog equal, default60s/capacity4; normal SIGTERM143, no forced kill, process gone/port free |
| Real64 diagnostic | Five ordered full responses equal main. Ordinary query after two prefix and two dense DISTINCT requests:11.974313→0.001842s, allocation5,474,297,528→1,632,032bytes, CPU11.514676→0.001873s |
| Other paths / limits | Prefix and indexed DISTINCT largely retain their prior allocation; exact counters retained. Single instrumented prewarmed-history observation, not cold/isolated ordinary, HTTP/P95, peak RSS or main-relative acceptance |
| Decision | Keep functional correction and fixed-workload parity; generic streaming, exact tuple and other documented gaps remain. Overall100% parity and main-relative10x P95 are not established |

Evidence is in `docs/go-server-baseline/native64-ordinary-projection-integration/`.
The baseline and candidate freshly verify all1152 real graph files on a COW
copy; no64 execution overlaps another. Background correctness/build work and
fixture-hash I/O are recorded. Collector/setup failures remain preserved: an
unaccounted Attempt12 test delta, speculative-state artifact equality, and the
PATH GNU cp clone flag. None is counted as a successful execution or discarded.


## 2026-09-07 — Attempt 14: synchronous generic DISTINCT cursor

Hypothesis: replace Attempt11's per-node producer/request rendezvous with
explicit synchronous candidate positions, retaining full decode, projection,
Java equality, source batches, required exhaustion and task ownership. Evaluate
the complete revised candidate against current ordinary/Attempt12/13 base.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `082a4736`; complete frozen patch `5a2e7eed0f304ec038932f4cb7cf51139b9f976fa6368d77142daaf2b8d7ae65`, incremental synchronous delta `50b779dc89b87796ec178d726300a7525204ad49a1c16c887073c21630032991` |
| Scope | Explicit positions replace per-node channels; shared A6/A7 preparation remains unchanged, lazy first demand, live per-wave context and separate source lifetime; consumer/provenance/task merge unchanged from reviewed generic executor |
| Correctness | Full-module race/vet, all2186 source/fixture files unchanged and equal root. Original1048 improves966→968 full main matches, exactly two scoped early-stop fixes; remaining80 retained. Generic432 outputs unchanged; all48 rolling responses equal,32 speculative40-source mappedView state changes retained |
| Independent tests | Standard context cancellation/task joins, fresh wave after successful child cancellation, first-demand preparation, Store.Close between demands, restartable legacy walk, owning results and Java UTF16 equality |
| Fixture | All64 real graphs,1152files/10,338,207,518bytes, isolated COW clone; all hashes checked before each sequential process and both original/clone after Close; no persistence disabled |
| Prefix first | Execute+marshal18.624456→9.812521s; allocation9,102,896,136→1,837,258,232bytes; CPU24.664961→53.656092s — explicit first-request CPU regression |
| Prefix repeat | Execute+marshal12.059742→2.204654s; allocation8,320,045,544→1,054,280,552bytes; CPU18.916477→13.878162s |
| Controls | Dense DISTINCT first4.118135→4.105638s, repeat4.117561→4.092250s; ordinary0.001797→0.001796s. Allocations essentially unchanged; all five complete output bodies equal main |
| HTTP | Independent actual shipping42/42 HTTP200, full typed bodies/headers/catalog64 equal; all239 source/embed inputs match root, default60s/capacity4, no profiling helpers or tuple code; SIGTERM143/no forced kill/port released |
| Decision | Keep lower observed prefix latency/allocation and repeat CPU, with explicit first-request CPU cost. Rejected per-node rendezvous remains absent |
| Limits | Single ordered instrumented observations with background correctness/build I/O; no overlapping64 execution, no CPU sampler, not HTTP/P95/peak RSS or main-relative10x acceptance |

`docs/go-server-baseline/native64-generic-sync-attempt14/` preserves the frozen
provider/review, exact combined source checks, full outputs/counters, real shipping
HTTP evidence and cleanup. Root independently repeated the42 typed response and
239 input checks. Collector failures for changed error/result object keys and an
existing ignored build output remain recorded; neither changed production/tests.
Exact tuples, main lazy candidate capabilities and streaming consumers remain
separate work; full parity and final benchmark-regression-gate are unproven.


### 2026-09-07 follow-up: exact projection tuple compatibility

This functional correction builds main's real retained tuple table at its
4096-posting/256-selected-value thresholds, before four-column admission. It
preserves full four-SID consumption, Java hash/probe order, actual structural
state, clear retention, cancellation rollback/retry and Close cleanup.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `06321518`; independent91-file tuple delta `1cec41089f4e897c110822ff7c11bfb44bb2d3cfca7ea6cc398c3eefda98de59`, seven production files; generic synchronous executor and Attempts12/13 preserved |
| Correctness | Provider and independent actual-main query/hash/error/cache/lifetime checks; root whole-module race/vet on ordinary and combined generic bases. All2271 final source/fixture hashes equal tested combination. Original1048 remains968 full matches/80 unchanged differences |
| Required behavior |16 full query cases,7 Java hash vectors,9 fresh-JVM SID/read-error cases, ordinary cache threshold956970→1055434→180296; independent concurrent build/clear/Close/cancel tests race×20 |
| Global64 path discovery | Fresh main and both Go revisions pass eight complete bodies including255/256/repeat, but actual tuple capacities stay0:64 sources select ParallelRaw/mapped view. Preserved as controls, not falsely counted as tuple construction |
| Routed real64 workload | All64 real graphs loaded; two explicit graphId routes of32 sources cover every graph. Each executes255/256/256-repeat, with six fresh main HTTP bodies. No CPU/scheduler override; all outputs equal. Actual candidate table counts0→31→62, baseline always0 |
| First32 construction |256 query execute+marshal0.121201→0.649740s; CPU0.577600→3.084234s; allocation76,895,128→263,266,016bytes — explicit construction regression |
| Last32 construction |256 query0.086002→0.417642s; CPU0.417305→1.948133s; allocation81,912,728→185,396,976bytes — explicit construction regression |
| Repeated probes | First32:0.120413→0.112753s, allocation76,887,056→48,153,288bytes; last32:0.083148→0.073162s, allocation81,915,720→47,795,600bytes. Single observations, not a general speedup |
| Shipping HTTP | Three fresh actual command processes pass8+6+42=56 complete typed main bodies/headers/catalog64.140 compiler/embed inputs and2271 source/fixture hashes match root. Default60s/capacity4, no profile helpers; all SIGTERM143/no forced kill/ports released |
| Fixture safety |1152 real graph files/10,338,207,518bytes. Separate COW main/native/HTTP clones; each run verifies hashes, real Close enabled. Original and clone graph contents remain unchanged after process exit |
| Decision | Keep required main construction behavior with visible first-construction latency/CPU/allocation cost. Do not substitute a result cache or fake byte accounting to avoid it |
| Limits | State observer reads pointer/array lengths outside request counters without scanning table contents. Single co-tenant instrumented diagnostics; no CPU sampler, no P95/peak-RSS/main-relative10x claim. JVM budget, warmed-JIT exception messages and broader lifecycle gaps remain |

`docs/go-server-baseline/native64-exact-tuple-integration/` retains provider and
independent freezes, both root integration checks, non-trigger and routed
diagnostics, fresh main HTTP bodies, all shipping phases and source/fixture
identities. Collector/setup failures remain recorded without source/test masking.
This corrects a functional boundary; overall100% parity and main-relative10x P95
still require their full independent acceptance evidence.


### 2026-09-07 follow-up: main-semantic lazy candidate sources

This functional correction separates main's selective candidate capability from
the stricter A6/A7 certificate and shares ordinary source helpers. Generic
DISTINCT now preserves selected-node decoding, concrete head ordering, owning
values, current-task cancellation and mapped/retained loader lifetime.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `10236487`;41-file provider incremental `aee8349b9c860736ab6a7dd51efb3ac0841603f9e291a2942a9e8dd4631a1cc7`; synchronous executor, Attempts12/13 and exact tuples preserved |
| Independent evidence |1205 provider and53 review files rehashed;14/16 production files byte-identical,2 existing tuple/decoder deltas reviewed;2260 untouched base module files preserved |
| Correctness |Whole-module race/vet pass,2301 source/testdata files match root. Original1048 improves968→994,26 new full main matches;34 main-success/native-error,16 different errors,4 different rows retained.580 DISTINCT/122 ordinary preserved;58 new source scenarios give122 full actual-main responses |
| History |All66 exported artifact responses match provider.45 speculative cancelled-task mappedView flag differences retained; no normalization of rows/errors or reduced denominator |
| Real64 |1152 persisted files/10,338,207,518bytes; separate COW copies, four sequential paired global8/routed6 processes, all14 complete bodies equal pinned main. Original/clone source and graph hashes unchanged after Close |
| Prefix first |Execute+marshal13.132074→16.762499s;CPU72.691150→81.477525s;allocation1,837,234,128→9,281,741,192bytes — explicit regression |
| Prefix repeat |2.485201→11.548890s;CPU16.217181→54.964670s;allocation1,054,277,592→3,759,881,000bytes — explicit regression |
| Dense history |First4.597434→0.314373s,CPU22.374278→2.066475s;repeat4.505829→0.295037s,CPU21.108569→1.795060s. Candidate prefix already built64 mapped views; not independent cold-query evidence |
| Tuple controls |Routed six full states identical, actual table counts0→31→62; construction and repeat costs broadly unchanged. Global64 tables remain0; all raw before/after observations retained |
| Shipping HTTP |Three fresh actual command processes,56/56 full typed main bodies/headers/catalogs;147 compiler/embed inputs and2301 source/fixture identities verified. Default60s/capacity4,no helpers; SIGTERM143/no forced kill/ports released |
| Decision |Keep required functional fidelity and shared candidate foundation, explicitly retain prefix latency/CPU/allocation regressions for a separate measured optimization |
| Limits |Single instrumented co-tenant observations,no CPU sampler,no P95/peakRSS/main-relative10x claim. B/C/D/E/relationship consumers and global JVM budget/GraphWork contracts remain separate incomplete work |

`docs/go-server-baseline/native64-main-source-integration/` preserves all frozen
inputs, root integration, full outputs, counter/state comparisons, shipping
verification and cleanup. The build collector's initial `/tmp` versus
`/private/tmp` error remains recorded; no source/test change masked it.
This is a functional follow-up, leaving optimization attempt15 unused and the
full compatibility/P95 objective and final benchmark-regression-gate open.


### 2026-09-07 follow-up: lazy filtered node consumers

This functional correction implements necessary-condition candidates, immediate
unbounded projection and generic filtered LIMIT consumption. Qualified DISTINCT
drains for provenance; scoped bounded generic preserves main's route-before-read
exception instead of returning early and hiding evaluation errors.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `9ada2bf1`;102-file consumer patch `b4f97957cc8ab4462ba62048d922792e9762214b3bc311eec823d557c1dbfe5a`;four production hashes match independent review,2299 unaffected base inputs preserved |
| Correctness |Whole-module race/vet;independent cancellation/fresh-request/Close and80-case migration race×10;2401 source/testdata inputs identical to root.Original1048 improves994→1016;C8/D8/E6 all equal;B28/F4 retained |
| New oracle |620 full responses:480 broad,96 conjunction,36 source-wave,8 independent scoped-route cases.All580 prior DISTINCT/122 ordinary preserved;67 exported artifacts retain every full response,53 speculative mappedView leaf differences recorded |
| Wire correction |Exactly12 lone-D800 response leaves in480 semantic oracle correspond to Java UTF8 question marks;2 actual main HTTP bodies independently prove boundary.Original semantic files/failures kept,no production or query/parameter normalization |
| Real64 |Fresh pinned-main13 HTTP bodies;5 controls+8 new C/D/E planner-proven requests.Both Go revisions return all13 complete bodies.All64 persisted graphs/1152files with separate COW clones,original/main/native contents unchanged after Close |
| Necessary ordinary |First execute+marshal12.130730→0.002318s,CPU16.865690→0.002344s,allocation13,743,344,640→2,698,568bytes.Repeat11.859974→0.001675s |
| Necessary DISTINCT |First12.324256→2.185858s,repeat12.281630→2.119354s;first allocation14,170,226,096→1,192,757,232bytes |
| Unbounded routed |Android zero rows11.531376→0.091208s;Kotlin54 rows11.541083→0.054738s.Main route consumption and complete response order preserved |
| Generic ordinary |12.952423→0.000533s;allocation3,890,732,256→780,536bytes |
| Generic DISTINCT regression |1.928698→6.492682s,CPU1.941437→6.470970s,allocation2,263,578,128→8,392,767,504bytes.Required full consumption/projection retained;separate optimization needed |
| Shipping HTTP |55/55 full typed bodies/headers/catalog64;150 compiler/embed+2401 module inputs match tested/root source.Default60s/capacity4,no helpers;both SIGTERM143/no forced kill,ports released |
| Decision |Keep functional fidelity and materialization removal,explicitly retain generic DISTINCT latency/CPU/allocation regression.No map-reuse optimization mixed in |
| Limits |Single instrumented co-tenant diagnostics,no CPU sampler,no P95/peakRSS/main-relative10x claim.B/relationships/lazy edges/EAGER strategy/global budgets and final benchmark-regression-gate remain open |

`docs/go-server-baseline/native64-lazy-filtered-integration/` contains freezes,
root combination evidence, fresh main bodies, planner admission proof, raw paired
measurements and actual command verification. An initial post-exit plain-bind
failure and subsequent no-listener/refused-connect/successful-bind probes remain;
the unchanged verifier later passed without restarting servers or changing data.
The remaining32 in the old suite are not the complete product gap inventory.


## 2026-09-07 — Attempt 15: scanner-owned projection binding map

| Item | Evidence / status |
|---|---|
| Hypothesis | Reuse one private binding map per generic DISTINCT scanner instead of allocating one for every projected expression; preserve consumption/evaluation/ownership/cancellation |
| Native base / candidate | `2ab90cdc`; provider patch `a2c0457f6c732f136711b58b2f5a73622d072de7882cd1258e236cd8fde30ef1`, one production file plus tests; all2402 combined inputs match root |
| Correctness | Whole-module race/vet; independent two-task real-cancellation/join/fresh-wave race×20; all67 complete response artifacts unchanged,45 speculative mappedView leaves retained; original1016/1048 unchanged |
| Real64 | All64 persisted graphs,1152files/10,338,207,518bytes; fixed five-query history, both full Go outputs equal pinned main; original/clone/source/binary unchanged after Close |
| Prefix first | Execute+marshal13.965664→13.844310s,CPU69.276597→67.477911s,allocation9,281,813,272→5,966,132,840bytes |
| Prefix repeat | 9.892465→9.800067s,CPU46.503897→45.741571s,allocation3,759,934,424→444,259,376bytes (88.18% reduction); latency virtually unchanged |
| Dense controls | First0.313969→0.312444s;repeat0.309357→0.303839s;allocation approximately268.4MB unchanged; prior prefix history retained |
| Ordinary control | 0.001809→0.001802s;allocation1,636,064→1,635,392bytes |
| Shipping HTTP | 55/55 full typed main bodies/headers/catalogs;150 active compiler/embed and2402 source/testdata inputs; two fresh processes exit143/no forced kill/ports released;1152 original/clone files unchanged |
| Decision | Keep substantial allocation reduction; no meaningful prefix latency or CPU breakthrough, no claim of reduced GC pauses; generic CDE DISTINCT regression remains separate |
| Limits | Single instrumented observations,CPU sampler off,getrusageCPU,forcedGC outside request; no P95/peakRSS/main-relative10x claim; full final gate outstanding |

`docs/go-server-baseline/native64-binding-map-attempt15/` retains the author,
independent allocation audit, root combination, every complete raw output and
counter/profile, shipping command evidence and setup/test collector failures.
The mutex contention diagnostic and label-map switch are separate hypotheses.


### 2026-09-07 follow-up: filtered node pagination and ORDER

This functional correction ports SKIP-before-projection, bounded ranking and
DISTINCT retained/evicted evaluation, source scoping and cancellation boundaries.

| Item | Evidence / status |
|---|---|
| Native base / candidate | `62b92d20`;32-file incremental `58f4e291d0a429d0f2f7a7e01cd78557ce164b041a01ce8ef63e3e3cf29b56a6`;three production files exact author,2401 unaffected inputs preserved |
| Correctness | Fullmodule race/vet and independent cancellation/join checks;2433 root inputs exact tested candidate;original1016→1044/1048,exact B28 fixes/F4 retained;generic408→432/432 |
| New oracle |595 complete JSON-value responses and traces;166 numeric spelling leaves retained,not byte equality;old67 captures61exact,3functional,3only76mappedView state leaves |
| Real64 |Fixed13+8 grounded B queries,fresh21 actualmain HTTP bodies;candidate21/21 complete matches,base20/21;all1152 persisted original/main/native/HTTPfiles unchanged after completed processes |
| Real row repair |Old DISTINCT ORDER returns <init>/getInstance;main andcandidate normalizeKey/purgeByMessageBox.Wrongbase retained as failure,invalid for speed comparison |
| Ordinary SKIP |13.296867→0.001228s,CPU13.348855→0.001270s,allocation3,812,181,784→1,043,152bytes |
| Routed generic ORDER |11.813967→0.171036s,CPU16.389199→0.171067s,allocation12,760,607,808→261,524,016bytes |
| DISTINCT SKIP regression |1.268922→1.535125s,CPU1.284297→1.576204s,allocation1,081,987,720→1,620,267,304bytes |
| Direct ORDER |Eligible ordinary0.017963→0.001457s,allocation9,566,080→22,791,224bytes;retained DISTINCT0.017785→0.002595s,allocation9,719,544→22,487,256bytes |
| Control costs |Prefixfirst13.552867→15.079963s,repeat10.028290→11.577072s;cause not established.Unchanged controls also slower;all raw paired costs retained |
| Shipping |63/63 fulltyped main bodies/headers/catalog64;152 activecompiler/embed+2433 module inputs;two fresh default60s/cap4 processes exit143/no forcedkill/ports released |
| Collector failure |Initial strictbaseline stops19th with fullbody mismatch/exit2 beforeClose.Retained unchanged;fresh same-harness full21 sweeps keep mismatch failure,close Stores,baseexit2/candidate0.No query/expected/production masking |
| Decision |Keep functional fidelity and large materialization removal;retain DISTINCT SKIP/allocation/control regressions for separate optimization |
| Limits |Single instrumented shared-host observations,not P95/peakRSS/main-relative10x;wrongbase query has no valid speed comparison;relationship/globalruntime/finalbenchmark gate remain open |

`docs/go-server-baseline/native64-streaming-pagination-integration/` retains all
provider/reviewer/root source evidence, setup and collector failures, fresh main
bodies, complete sweeps, actual command replays and post-exit fixture identities.
This is a functional follow-up; Attempts16/17 remain separate hypotheses.


## 2026-09-07 — Attempt 16: fixed label alias switch

| Item | Evidence / status |
|---|---|
| Hypothesis | Remove the per-call nine-entry alias map from matchesLabel while preserving lowercasing, Node/Constant precedence, exact alias comparison and EqualFold fallback |
| Native base / candidate | `4e94017f`;two-file author patch `b86c4e149afe22e083c06725475ffa0b383dc73570c037169afe3788e8dc4ee3`,onlyproperties.go in production;2434 candidate/root inputs exact full tested combination |
| Independent evidence |157author/102integration/56prior allocation-audit files rehashed;actual old helper body independently matched production;A12/A13/A15/B/CDE/tuple preserved |
| Correctness | Fullmodule race/vet;4275 Unicode/invalidUTF8/kind boundaries plus9aliases×21kinds×3spellings;26main Constant responses unchanged;original1044/1048,generic432,B595 unchanged |
| Historical state |76previous artifacts74rawexact,2only51speculative mappedView leaves;all complete publicresponses preserved;166Bnumeric spellings explicitly not byte equality |
| Real64 |All64 persisted graphs/1152files/10,338,207,518bytes;two fresh fixed21-query processes,42/42 fulltyped main bodies andallsourcehistories equal;original/clone/source/binary unchanged afterClose |
| Generic DISTINCT |6.516011→5.349303s,CPU6.437094→5.347743s,allocation8,392,826,728→5,479,520,392bytes (34.71%less);bothNumGC0/pause0 |
| Generic routes |Android0.097497→0.077069s,allocation137,483,136→79,408,752bytes;Kotlin0.055697→0.043519s,allocation82,170,112→47,950,416bytes |
| B generic ORDER |0.172548→0.149570s,allocation261,532,688→203,451,424bytes |
| Prefix control |First13.657089→13.488717s,repeat9.952376→9.991023s;allocation broadly unchanged,no prefix breakthrough |
| Shipping |63/63 fulltyped main bodies/headers/catalog64;152active compiler/embed+2434module inputs;two fresh default60s/cap4 processes exit143/no forcedkill/portsfree;all1152original/HTTPclone files unchanged |
| Decision |Keep measured generic-path allocation/CPU/wall benefit;retain slightly slower dense controls andremaining generic regression.No node boxing/context/candidate changes bundled |
| Limits |Single instrumented pair;known agents paused heavywork,external activityuncontrolled;no P95/peakRSS/GCpause/main-relative10x claim.Final benchmark gate andfullcompatibility remainopen |

`docs/go-server-baseline/native64-label-alias-attempt16/` preserves exact source,
full frozen author/integrator evidence,old-reference checks,all21-query raw
profiles/receipts,HTTP63 verification andpost-exit fixture identities.


## 2026-09-07 — Attempt 17: projection context cancellation fast checks

| Item | Evidence / status |
|---|---|
| Hypothesis | Avoid shared cancelCtx.Err mutex acquisition while Done remains open at five existing Store projection checkpoints; preserve current-context cancellation and failure ordering |
| Native base / candidate | b7bb15a2; six-file patch271f166fc60570ee329ebbd8cb1b1786194328bb1bfc3c82c5d343316605905d; two production files; all2436 root inputs equal full tested candidate |
| Diagnosis | A15 same-binary mutex sample estimates13.77s cancelCtx.Err delay of24.99 aggregate; sampling perturbs repeat9.874752->10.736632s. Delay is not CPU/wall fraction or speed prediction |
| Correctness | Fullmodule race/vet; actual original-production named tests24PASS; independent typed-SID-first-error race10; original1044/1048,F4 retained; generic432/B595 unchanged |
| Historical state | 76 complete artifacts74rawexact,2only61mappedView leaves; no retained differences in integration. Author four retained coordinates independently shown reachable by original repeats, raw failures retained;166 B numeric spellings retained |
| Real64 | 64 persisted graphs/1152files/10,338,207,518bytes; two fresh fixed21 processes;42 fulltyped responses and all source histories equal; both Close/exit0,original/clone/source/binary unchanged |
| Prefix first | 15.676783->1.754574s,CPU76.806707->13.262513s,allocation5,966,082,120->5,965,804,912bytes |
| Prefix repeat | 11.311840->0.997778s,CPU52.879306->6.837113s,allocation444,206,968->443,947,144bytes |
| Necessary DISTINCT | First2.085637->0.207867s,repeat2.158655->0.177637s;first CPU9.994055->1.537487s;allocation virtually unchanged |
| Remaining controls | Dense first0.318998->0.305078s;generic DISTINCT5.409688->5.415945s/about5.48GB unchanged;slower generic routes and B controls retained |
| Shipping | 63 fulltyped main responses/headers/catalog64;152active compiler/embed+2436module inputs;two fresh default60s/cap4 processes exit143/no forcedkill/portsfree;1152original/HTTPclone hashes unchanged |
| Decision | Keep substantial measured prefix/necessary-DISTINCT latency and CPU benefit; no allocation or established GC-pause claim; generic slot optimization remains separate |
| Limits | Single instrumented pair vs preceding Go,known agents quiet/external host activity uncontrolled;not main-relative P95/10x proof;full compatibility and final benchmark gate remain open |

`docs/go-server-baseline/native64-projection-context-attempt17/` preserves the
diagnosis, exact provider/integration/source evidence, all raw query profiles,
complete bodies and state histories, actual HTTP verification, fixture receipts,
and initial observer/collector failures. No async CPU sampler enters shipping.


## 2026-09-07 — Attempt 18: generic candidate slot without registry escape

| Item | Evidence / status |
|---|---|
| Hypothesis | Reuse one private existing candidateSlot only in the generic lazyFiltered loop when rowOrders is nil; preserve complete-node decode and owned-value fallback for registry bindings |
| Native base / candidate | fb4434df; patch259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d; one functional production file, borrowing-comment update and one new test;2437 root inputs match full tested combination |
| Correctness | Fullmodule race/vet; exact-original overlay5top-level+152subtests;18 independent named ownership/Close/cancel/join race executions; original1044/1048 andB595+53traces unchanged |
| Historical state | 76 artifacts73rawexact,3only51mappedView40source leaves; no retained/public differences;166 numeric spellings preserved; guard-disabled negative control detects borrowed pointer escape |
| Real64 | All64 persisted graphs/1152files/10,338,207,518bytes; both fixed21 processes Close/exit0;42 fulltyped main bodies andallsourcehistories equal; original/native/source/binary unchanged |
| Generic DISTINCT | 5.391398->4.900908s, CPU5.385597->4.896566s, allocation5,479,520,024->2,249,485,384bytes |
| Generic ordinary | 0.000529->0.000499s, CPU0.000558->0.000535s, allocation687,528->603,064bytes |
| Unbounded Android | 0.074303->0.067068s, CPU0.074272->0.067060s, allocation79,409,296->15,021,024bytes |
| Unbounded Kotlin | 0.044406->0.040840s, CPU0.044426->0.040844s, allocation47,950,624->10,026,672bytes |
| Prefix control | 0.948603->1.006266s, CPU6.979380->7.420089s, allocation443,945,336->443,936,888bytes |
| Dense control | 0.305769->0.318127s, CPU1.975691->2.150681s, allocation268,438,296->268,425,552bytes |
| Shipping | 63 fulltyped main bodies/headers/catalog64;152active compiler/embed+2437module inputs;two default60s/cap4 processes exit143/no forcedkill/portsfree;1152original/HTTPclone hashes unchanged |
| Decision | Keep measured generic allocation reduction; unchanged consumption/projection/ownership; retain every control and counter, no other map/decoder/source/equality optimization bundled |
| Limits | Single instrumented pair vs preceding Go; known agents quiet/external activity uncontrolled; no main-relative P95/10x, peakRSS or established GC-pause claim; full compatibility/finalgate remain open |

`docs/go-server-baseline/native64-lazy-slot-attempt18/` preserves the read-only
diagnosis, exact author and independent source/evidence freezes, initial test
failures and negative controls, all real64 measurements and complete outputs,
shipping verification and source/fixture identities.


## 2026-09-07 — Attempt 19: compiled string atoms without temporary expressions

| Item | Evidence / status |
|---|---|
| Hypothesis | Avoid Literal/Binary interface construction for three compiled string operators; share the exact old string matcher, retaining both literal checks and matching/type/lower/fallback order |
| Native base / candidate | 39eedb33; patchdfadcbdadfc32ff638bfcce71b0774e60cdf60241e09e710d2c1512cb60c0dfc; four changed files;2439 root inputs equal full tested combination |
| Correctness | Fullmodule race/vet; exact old helper and byte-exact matcher extraction;27×27×6×2 boundaries; original-eval-only overlay3named/60cancel subcases;66 independent operand/error controls |
| History | Original1044/1048,B595/53traces and166 numeric spellings unchanged; independent76 outputs73exact,71mappedView+2g8retained leaves, full public bodies unchanged |
| State proof | Exact-original39 task7/g8 delayed until scheduler's own LIMIT cancellation produces the two retained=false coordinates with all3 main responses equal; original strict failure and natural3true runs kept; no distribution equivalence or broad mask |
| Real64 | 64 persisted graphs/1152files/10,338,207,518bytes; fixed21 paired processes Close/exit0;42 typed bodies andallsourcehistories equal; original/native/source/binary identities unchanged |
| Dense first | 0.318904->0.306293s, CPU2.132667->2.027935s, allocation268,471,360->170,248,416bytes |
| Dense repeat | 0.315795->0.288127s, CPU2.110352->1.849766s, allocation268,440,984->170,203,144bytes |
| Prefix first control | 1.581503->1.670474s, CPU12.750241->13.238413s, allocation5,965,813,128->5,940,670,248bytes |
| Prefix repeat control | 0.963427->0.976493s, CPU6.686479->6.735293s, allocation443,938,328->443,943,736bytes |
| Generic E control | 4.998293->4.885424s, CPU4.934228->4.881990s, allocation2,249,485,336->2,249,487,240bytes |
| Shipping | 63 typed main bodies/headers/catalog64;153 compiler/embed+2439module inputs;two default60s/cap4 processes exit143/no forcedkill/portsfree;1152original/HTTPclone hashes unchanged |
| Decision | Keep dense allocation/CPU benefit; retain prefix andother control regressions;all42 measured requestsGC0, noGCpause improvement claim; no other optimization bundled |
| Limits | Single instrumented pair vs preceding Go,known agents quiet/external activity uncontrolled; not main-relative P95/10x or universalcompatibility; full finalgate remains open |

`docs/go-server-baseline/native64-atom-predicate-attempt19/` retains the exact
author/reviewer/diagnostic evidence, initial quadratic-test timeout and strict
state failures, permitted scheduling proof, all complete real64 outputs and
counters, actual shipping verification and post-exit source/fixture identities.


### 2026-09-08 — Functional follow-up: audit the original64 benchmark testcases

Actual invocation of the pinned-main workload generator exports1,267 cases for
64 graphs and coverageFamily=all, with a second JVM export byte-exact. The old
HTTP42 manifest maps only34 global-wide cases (31 query texts equal ignoring
whitespace and3 parameter-to-literal adaptations);1,233 original cases are absent.
Its extra8 wrapped queries originate in a separate four-corpus benchmark.
Graph order, original replay order, cold/warm/startup-prepared preparation,
parameter transport, request-selected source sets and timing boundaries also
remain different or unreplicated. No performance run was started by this audit.

The actual case objects, full coverage/missing-ID list, source hashes, exporter,
build logs and verification are in
`docs/go-server-baseline/native64-testcase-audit-20260908/`.
`graphite-server/scripts/README.md` now explicitly treats HTTP42 as diagnostic
coverage. Full testcase replication and result/source-state validation must
precede per-case P95 acceptance. This corrects prior overbroad acceptance framing;
earlier HTTP passes and single instrumented measurements keep only their stated
local scope. The overall100% parity and10x goal remains incomplete.


### 2026-09-08 — Functional follow-up: ingest all1,267 main64 cases in Go

Added a strict workload loader and a definition/AST validation command. The
committed fixture is the exact actual-main export378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023.
Every case field, graph/replay order,321 parameter maps and original source
selection is preserved. Independent comparison of the actual Go report confirms
all1,267 records and inputs; all native parsers accept the unchanged queries.
Concrete AST/binding/order/ownership/rejection tests and fullmodule race/vet pass.
No graph loads, query executions or performance measurements occurred.

`docs/go-server-baseline/native64-testcase-loader-20260908/` retains the complete
Go case/AST report, independent verification and test receipts. Explicit
request-selected scope propagation and main's index-state lifecycle remain
runtime gaps to fix before full real64 replay and per-case P95 acceptance.


### 2026-09-08 — Functional follow-up: request-selected scope and context policies

Added `ExecuteCrossWithOptions` and HTTP propagation of main's already-selected
source marker and execution-context planner policies. Explicit selection of
every source remains distinct from unscoped/allGraphs execution. Scoped
ordinary/residual queries preserve persisted-source preference; streaming
queries avoid a second root pruning pass, and full split-source scans preserve
main's retained-index policy. Finite work-budget accounting remains unfinished.

Actual pinned-main capture covers160 scenarios/320 responses with1/2/8/40/64
tiny persisted correctness sources,8 query shapes,scope and bad-last variants,
each repeated twice. Original Go has24 public differences; candidate has0.
Deterministic states match. Twelve additional actual-main scheduling controls
prove canceled last-wave sibling retained states; six forced Go schedules match
full states and join every started sibling. Raw natural-state differences are
preserved with explicit lifecycle assertions and negative controls, not erased.
Six HTTP body/state cases verify propagation. Full-module race tests and vet
pass. Initial strict-state and harness/expectation failures remain archived.

`docs/go-server-baseline/native-request-source-scope/` contains the actual main
outputs, original/candidate captures, source/JAR authentication, independent
verifier and complete test logs. This is a functional prerequisite, not
Attempt20 or a performance result. Full1,267 real64 replay, index-state setup,
per-case P95 and the final100%/10x gates remain incomplete.


### 2026-09-08 — Functional follow-up: capture main index-state lifecycle

Actual pinned-main capture now covers10 lazy/startup scenarios and70 operations
using copied tiny persisted correctness fixtures:30 full query responses,
30 clears and10 preparations. Existing/missing/corrupt sidecars and two bad-node
variants show that clear may persist a built index first, and startup-prepared
query behavior cannot be reconstructed from a retained-index flag alone. Both
bad-node startup scenarios first succeed, then return the original decode error
after clearing. These are main observations, not native parity claims.

Independent checks verify20 successful responses/10 expected errors, empty
inspected cache state after every clear, and exactly6 generated/replaced index
files with all other fixture bytes unchanged. Captures, original file hashes,
post-run fixtures, exact source/JAR hashes and verifier are retained in
`docs/go-server-baseline/native-index-lifecycle/`. Native lifecycle APIs and
full1,267-case real64 state replay remain unfinished; no performance was run.


### 2026-09-08 — Functional follow-up: native index preparation and clear

Added optional startup preparation before graph publication, explicit complete
preparation, and invocation-boundary index clearing without closing graph/node
ownership. Clear preserves main's persistence-before-release behavior, resets
query indexes/caches and cold certificates, and waits for a first index loader.
The owner must join queries and release their index handles before this boundary.

All30 actual-main public responses/errors,70 operations and150 structural/file
states match across the prior10 scenarios. Two raw-cache counters are explicitly
unavailable in the native observer; the corpus has zero values and does not
prove their broader parity. New actual-main empty/short-string fixtures reject
the initial assumption that zero trigram postings count as prepared: both
preparations return false, short-string structural postings remain retained,
and no index is persisted. The initial failing candidate/log is preserved;
the final code keeps the existing persistence guard unchanged.

Full-module race and vet pass, with repeated-clear graph readability, cache
rebuild, cancellation and first-loader joining checks. Evidence is in
`docs/go-server-baseline/native-index-lifecycle-implementation/`. These are
functional prerequisites only: full1,267 real64 state replay and per-case P95
acceptance remain unfinished; no performance measurement was run.


## 2026-09-08 — Attempt 21: main filtered-string COUNT aggregation

| Item | Evidence / status |
| --- | --- |
| Hypothesis | Port actual main filtered COUNT admission, source routing and posting aggregation so eligible queries avoid the generic full-node/trigram certificates; retain those certificates for existing consumers |
| Native base / candidate | 9237131f; five implementation/test files in the attempt manifest; identical measured native binary b49ffae0ab98c09c453d726af334df4e9b1234496debbffa79b43391aafcdee4 |
| Main reference | 4e328b0109e13c896b74004823fb049fcb19251a, original Explore JAR and full 1,267 ordered case definitions |
| Real dataset | 64 persisted graphs; 1,152 original files; 10,338,207,518 bytes; immutable reference and separate cloned runtime fixtures |
| Functional evidence | 194 actual-main scenarios/388 observations: old dispatch has 72 public differences, candidate has eight existing gaps; nine actual posting primitive observations and 400 canceled-sibling scheduling observations |
| Real64 correctness | Cold and startup replay plus warm prewarm each cover 1,267 cases; 1,266 exact successful results and same original error per state; seven fields match over 486,848 graph observations; all original files unchanged |
| Initial rejection and correction | First cold/warm captures each had two dense COUNT provenance-order mismatches; four actual-main Unicode/source-order cases yielded eight pre-fix failures; Java UTF-16 result ordering fixes them without changing task order |
| Tests | Final go test -race ./... exit 0, query actually ran 52.516s while some unaffected packages were cached; go vet ./... exit 0; exact commands and source hashes retained |
| Baseline CPU / memory | Full ordered profile brackets case 838: generic preparation 9.25s/94.97% sampled CPU; candidate certification 5.16s/52.98%, trigram certification 4.03s/41.38% (overlapping cumulative paths); 2,184,529,752 allocated bytes, GC count/pause deltas zero, including profiler overhead |
| Cold COUNT, old Go → candidate / fresh main | zero 11,055.763 → 0.905 / 1.777ms; targeted 1,958.953 → 85.955 / 20.437ms; dense 885.805 → 88.714 / 17.314ms |
| Cold DISTINCT COUNT, old Go → candidate / fresh main | zero 2.191 → 1.105 / 0.920ms; targeted 3,567.049 → 143.485 / 28.157ms; dense 3,362.056 → 95.243 / 48.199ms |
| Whole ordered pilot | Successful-case Go time sum cold 95.192 → 74.240s, startup 93.919 → 74.007s; n=1 per case, not P95; largest later-case increases regex-or-zero +428.224/+311.468ms, all deltas retained |
| Fresh-main comparison | Candidate slower on 1,180/1,266 cold and 1,184/1,266 startup successes; observed ratio >=10 on 1 cold and 66 startup cases; these are single-sample counts, not acceptance |
| Input/process verification | Four serial processes terminal; 554 main and 1,008 native frozen inputs unchanged; no new errors/timeouts; every runtime preserves all 1,152 original files |
| Decision | Keep the public-main COUNT path and its demonstrated local improvement; no comparable later 11s increase in these runs; no candidate CPU/allocation reduction claim without measurement |
| Outstanding | Eight original oracle gaps, main's original failed case/all-success gate, formal warm replay, configured workers/work metrics, full server fidelity, repeated per-case P95/10x and required PR benchmark gates remain open |

Evidence is in `docs/go-server-baseline/native64-filtered-count-attempt21/`,
with actual-main contract/oracles in `native-filtered-string-aggregation/` and
baseline CPU attribution in `native64-filtered-count-profile/` alongside it.
Initial failed captures and strict-state test failures remain archived. This
attempt does not change the pinned main reference or remove its failing case.


### 2026-09-08 — Functional follow-up: public source construction and empty IDs

Aligned public entry order with actual main: all source objects must have non-null
graphs before duplicate IDs are checked; one empty ID is valid; complete parsing
precedes query cancellation and execution. Duplicate IDs now report the original
IllegalArgumentException class/message. Qualified nodes, methods, relationships
and paths preserve empty graphId and metadata through a separate qualification
flag/type rather than treating an empty string as missing. Public cancellation
preserves main's class/message and explicitly supplied typed reasons while keeping
Go errors.Is(context.Canceled); ordinary DeadlineExceeded is not relabeled with
an invented timeout duration. The standalone ParseContext API remains unchanged.

Native base f5b05027; main reference remains 4e328b0109e13c896b74004823fb049fcb19251a.
The final actual-main corpus has 298 scenarios, repeated in a fresh run for 596
calls: constructor/error ordering, empty/shared namespaces, Method properties,
node/relationship/path qualification, bound relationships, zero-hop paths and
elementId seeks. Full public/state observations repeat exactly; each run preserves
5,836 original fixture files with 152 explicitly audited generated sidecars.
The old Go source overlay has 248 differing cases; the candidate has zero public,
state, scalar-type or container-kind differences. Initial 264-case controls,
12 fixture-precondition failures and two pre-test unused-import failures remain.

Full-module go test -race -count=1 ./... and go vet ./... pass; 2,521 module/oracle
inputs are unchanged. Six duplicate-ID exceptions were removed from the previous
filtered COUNT corpus, now 386/388 public observations exact; only the two repeated
unknown-label differences remain. Independent source review found no new defect.

Final real64 cold replay, warm prewarm and startup-prepared replay each preserve
all 1,267 ordered cases, 1,266 successful results and the one original error.
Seven state fields match across 486,848 graph observations; 1,152 original graph
files remain unchanged in every runtime. Runtime exit 1 preserves the original
all-success gate failure; comparator exit 0 confirms parity. Warm formal replay
remains unavailable. The 2,519-file frozen module matches the final tested source.

Keep the functional correction. Evidence is in
`docs/go-server-baseline/native-source-constructor/`. No performance measurement
was made in this follow-up; prior latency samples are not reattributed to this
candidate. Full server/engine parity, unknown-label routing, work/metrics policy,
the original reference failure, per-case P95/10x and required PR gates remain open.


### 2026-09-08 — Functional follow-up: general candidate routing and read order

Against Go `4ac9dd8b5f5fcfaf9ec920d7f7d381faedb5fe88`, port original main
`4e328b0109e13c896b74004823fb049fcb19251a` unknown-label dispatch, candidate
class/binding order, mapped node-offset lookup and literal elementId seek.
Unknown labels enter general execution before all fast paths. Any Method label
selects metadata; otherwise first-label class resolution precedes existing
bindings. Fresh mapped single-label candidates preserve the JVM iterator's erased
cast; multilabel, bound and seek paths retain their separate class checks.

Independent review also found general WHERE was interleaved with candidate reads.
General non-optional MATCH now materializes patterns across all inputs before
filtering; OPTIONAL materializes per input. This removes the former general
indexed/streaming scanner whose skipped reads could change errors. Positive early
limits retain lazy traversal, with seeks attempted first even for MATCH following
RETURN LIMIT. Actual original-main controls verify both error-order corrections.

There are 140 routing/type/offset cases and eight general WHERE/seek-order cases,
each executed twice in the pinned JVM: 296 matching public/type/state observations.
Final Go has 148 primary and 81 independent single-source auxiliary calls with zero
differences. The complete old-production overlay has 60 routing plus two WHERE
primary differences, and 36 plus two auxiliary differences. Original 120/6 controls,
failed before captures and the explicitly non-final intermediate 140 candidate are
retained. Final candidate source hashes are unchanged across its run.

Full-module `go test -race -count=1 ./...` and `go vet ./...` pass, with 2,595
module/oracle inputs unchanged. The prior constructor corpus retains 298 exact
cases, and all 388 filtered COUNT public observations now compare strictly without
the old unknown-label exception; the independently proven sibling scheduling
allowance remains bounded to its original state coordinate.

A separate immutable 2,523-file module completes real64 cold replay, warm prewarm
and startup-prepared replay. Each preserves all 1,267 cases, 1,266 successful results
and the original failure; seven state fields match over 486,848 observations and
all 1,152 original files remain unchanged per runtime. All comparators pass while
runtime exit 1 retains the original all-success gate failure. Three raw response
streams are archived with decompressed-byte hashes verified.

Keep the functional correction. Evidence and reproducible commands are in
`docs/go-server-baseline/native-unknown-label-routing/`. No latency/P95 measurement
was made or attributed to this candidate. Mapped supertype order and deliberately
inconsistent type/node indexes, non-CallSite malformed decoding, work metrics and
worker configuration, remaining JVM-backed CLI paths, formal warm replay, repeated
per-case P95/10x and required PR benchmark gates remain open.


### 2026-09-08 — Measurement follow-up: complete per-case latency sampling

Freeze Go engine `3b5ecae5c9a4c1425f18a3b5af3e373b41a184eb` and original main
`4e328b0109e13c896b74004823fb049fcb19251a` in an independently hashed measurement
build. The controller runs full ordered 1,267-case workloads on independent clones
of the same 64 real persisted graphs (1,152 files), with paired runtime order
alternating and state order rotating. Each runtime is a fresh process, timed runs
are serial, and original main retains its timer, executor, sampler and counters.
No production query engine changes are part of this measurement follow-up.

The three-state smoke capture completed cold, startup-prepared and the explicitly
diagnostic warm-after-failed-prewarm state. All six runtime processes completed
all 1,267 cases: 1,266 canonical successes match and the one original
four-or-graph-id-targeted failure is preserved. Both diagnostic prewarm ledgers
also contain all 1,267 original signatures. Runtime exits remain 1 for the
original all-success gate; the three independent pair verifications pass.
Fixture and frozen-input audits pass. Generated clones were removed only after
both processes terminated and pair verification succeeded.

This capture has exactly one sample per case/runtime/state, so it establishes
measurement plumbing and correctness, not P95 or speedup. The summarizer keeps
3,801 separate state/case rows, reports successful latency separately from failure
time and censored timeouts, and labels every smoke row insufficient. Acceptance
requires at least 200 samples per case/runtime/state and main P95 / Go P95 >=10
for each successful case. The original failed query and diagnostic warm status,
Go resource/work accounting gaps, and remaining server fidelity still block
full acceptance; the controller explicitly remains ineligible.

Before freezing this measurement build, full-module go test -race -count=1 ./...
and go vet ./... passed, as did nine statistics tests and original-main tooling
verification, with 2,533 inputs unchanged. The archive independently verifies all
79 copied files, canonical ledgers and raw/normalized clocks. Complete commands,
environment, raw timing/CPU/GC counter records, build closure and limitations are
in `docs/go-server-baseline/native64-p95-sampling/`. The three-pair capture and
summary are retained under its evidence directory; no 200-sample campaign has
been run or attributed to this follow-up. Keep the measurement tooling.


### 2026-09-08 — Attempt 22: retain successful parsed-query ASTs

Hypothesis: main's real64 setup fills its process-wide parsed-AST cache, while Go
repeated lexer/parser/adapter work inside every execution. Retaining the same raw
queries should remove that repeated work across the complete workload. Baseline
Go is `bdcbd969917529f31b8ade101bae1ee15d89fa93` (same engine as `3b5ecae5`);
reference main remains `4e328b0109e13c896b74004823fb049fcb19251a`.

Port the 1,024-entry access-order cache, successful nonblank/raw-string keys,
semicolon child entries, partial-failure behavior and original retained-byte
estimate. The runtime uses min(16 MiB, Go soft memory limit / 128), with the
16 MiB ceiling in this benchmark configuration. Go soft memory limit and JVM max
heap are explicitly different settings. Cache trees stay private; public parsing
returns owned deep copies, including nested maps/slices and hop pointers. Query
parameters and results remain request-specific. Execution parse/error/cancel
ordering is unchanged, and ParseContext polls within cached-expression copies.
This adapts Java immutable identity to owned mutable Go values, rather than
claiming identical Java reference identity or mutation exceptions.

Actual pinned JVM runs capture 35 cache events at 512 MiB twice and 8 GiB once;
the repeated smaller-heap output is identical. They verify raw keys, exact LRU
and byte-budget boundaries, errors, partial semicolon success, 31 deep mutation
rejections and four parameter/graph execution controls per run. Go tests compare
actual main state transitions plus cache isolation, concurrency, cancellation,
error precedence, and all 750 unique queries in the complete real64 case matrix.

Full-module go test -race -count=1 ./... and go vet ./... pass; 2,535 inputs remain
unchanged. Three correctness captures use a separate immutable 2,529-file module
and the same 64 real persisted graphs / 1,152 files. Each covers all 1,267 cases,
1,266 successful results and the one original error, with zero public differences.
Seven graph-state fields match across 486,848 observations. Comparator exits are
zero while the original all-success runtime gate exits one. Raw response archives
have independently verified decompressed hashes. The small oracle fixtures were
used only for correctness, never performance.

A frozen measurement source commit, `6182822983971ab1ec9dbee1023665a60c36e465`,
then completes three serial main/native paired timing runs. Its incremental Git
bundle is retained; all 279 internal Go files match that commit and the binary's
frozen source. All three pairs preserve every signature and original error,
including both full prewarm ledgers in the diagnostic third state. There are no
timeouts or new differences. This is n=1 per case/runtime/state, not P95.

| State | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Go slower than main cases |
| --- | ---: | ---: | ---: | ---: |
| cold | 74.280 | 74.301 | 24.640 | 981 / 1266 |
| startup-prepared | 73.782 | 73.715 | 24.990 | 910 / 1266 |
| warm-after-failed-prewarm (diagnostic) | 67.537 | 67.763 | 19.441 | 1221 / 1266 |

Across cases, the median old-Go / candidate-Go single-observation ratios are
2.288, 2.256 and 2.262. These are point-ratio distributions, not latency P95.
Total successful-query time is effectively unchanged in these observations;
no statistical regression/no-regression conclusion is established. The dominant
native regex cases remain roughly 24.5s and 7.4s. Main's original process CPU,
heap/RSS and GC counters are retained; comparable sampled native resource
accounting is still unavailable, so no native CPU/memory gain is claimed.

Keep AST caching: it reproduces the original successful-AST lifecycle and removes
repeated parser work, with better point estimates for many cases and strict
correctness/state parity. The one-sample summary retains all 3,801 state/case rows
as insufficient, and its nonzero exit preserves unproven acceptance. The original
failed query, formal warm gate, full work/resource/configuration equivalence,
remaining server features and 10x per-case P95 remain open. Full commands,
environment, raw captures and limitations are in
`docs/go-server-baseline/native-ast-cache/`.

### 2026-09-08 — Functional follow-up: preserve Java regex quote preprocessing

Independent actual-Java tests exposed a preexisting compatibility gap in Go
`bc708a810fce0d111988c72a3105820bfd1cf06e`: quote delimiters were handled inside
parsing, whereas Java17 transforms quoted text before parsing. This changes both
valid pattern semantics and syntax-error positions. For example, `a\Q\E?`
should match empty text; the old Go engine returns false. The malformed pattern
`.*\Qfoo\Ebar\E.*` has Java error index 9 but the old Go engine reports 13.

Apply the original quote preprocessing to decoded code points, retaining original
source text in diagnostics. Match Java's first quoted digit escaping, punctuation,
backslashes, empty/multiple/unclosed quotes and cancellation during scan/expansion.
Error caret rendering preserves original UTF-16 units and TAB characters. This
is a functional correction, separately committed before the quoted-contains
performance hypothesis; no matcher fast path is included.

Actual Java17 corpora contain 609 plus 77 cases, each captured twice with complete
identical outputs. All 686 native assertions pass after this correction; an
isolated previous-Go run retains 88 failing cases and 598 passing cases. An
independent syntax-integration corpus adds 2,030 case/text combinations, including
classes, quantifiers, comments mode and preceding escape fragments. Two actual
Java and two functional-only Go runs agree exactly; the old engine differs on
174 cases. These are separate corpora, not additive counts of distinct bugs.

Full-module race tests and vet pass; all 16,498 recorded inputs remain unchanged.
The initial isolated-copy run omitted three sibling Kotlin source files required
by the existing OpenAPI drift test. That failed run is retained; the complete
module checks pass after adding those inputs without changing production code.

A separately frozen functional-only module completes the full original real64
workload in cold replay, warm prewarm and startup-prepared replay. Every capture
has all 1,267 cases, with 1,266 successful results and the original error preserved.
Public output differences and graph-state differences are zero across 486,848
observations. All 1,152 original files in the same 64 real persisted graphs remain
unchanged per runtime. Three comparator exits are zero; runtime exits remain one
because the original all-success gate fails. Formal warm remains unprepared.

Keep the semantic correction. No latency, CPU, allocation or memory improvement
is claimed, and these captures are not P95 measurements. Original main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. Full commands, frozen inputs, original
failures and verified response archives are retained in
`docs/go-server-baseline/native-regex-quoted-contains/quote-functional/`, with the
actual-Java parent oracles alongside. Full server fidelity, required benchmark
checks and the 10x per-case P95 target remain open.

### 2026-09-08 — Attempt 23: compile strict quoted-contains matcher plans

Hypothesis: the three original real64 quoted-literal regex cases spend substantial
CPU and allocation in general matcher execution and input code-point decoding.
After the separate Java quote semantic correction, baseline Go is `0d055463`;
original main remains `4e328b0109e13c896b74004823fb049fcb19251a`.

Preserve ordinary regex compilation and errors, then retain an immutable KMP
prefix table for exactly `.*\Qliteral\E.*` with a nonempty ASCII literal, no CR/LF
and no embedded quote terminator. ASCII input uses linear literal search with
whole-input line checks; non-ASCII/WTF8 and every other pattern use the complete
matcher. Context checks cover long input, prefix construction and fallback loops.
Candidate scans, property reads, expression order, eager OR and error propagation
are unchanged. This is one matcher hypothesis, separate from the functional fix.

Full-module race tests and vet pass, with 2,542 recorded inputs unchanged. Tests
cover actual-Java results/errors, whole-string line rules, all ASCII bytes,
Unicode/WTF8 fallback, overlapping and long literals, cancellation, concurrency,
public null/type/error ordering, parameter reuse and compile-cache eviction.
New full real64 correctness captures use the same 64 physical graphs / 1,152
original files and all 1,267 ordered cases in cold, warm prewarm and
startup-prepared states; terminal comparison and archive evidence is retained.
The original failed case and unprepared formal warm remain explicit.

The before and after diagnostic profiles use identical command/helper bytes,
the complete original workload and no additional forced GC. All 1,267 signatures
are preserved. The earlier profile precedes the independent functional quote
correction; exact source and executable archives preserve that difference.

| Case | Before TotalAlloc (bytes) | Candidate TotalAlloc (bytes) | Before process CPU (s) | Candidate process CPU (s) |
| --- | ---: | ---: | ---: | ---: |
| regex-or-zero | 21,033,952,776 | 2,326,225,432 | 34.547250 | 11.178630 |
| regex-or-targeted | 6,058,040,768 | 743,596,368 | 12.404417 | 3.527174 |
| regex-or-dense | 1,879,528 | 1,636,208 | 0.001382 | 0.000937 |

These process-counter brackets include profiler bookkeeping. All profiled clocks
are contaminated diagnostics, not latency acceptance. Sampled allocation/heap
differences can lag GC; exact TotalAlloc deltas are reported above. The dense case
has no CPU samples. Afterward, ProjectionCandidateNode occupies 52.64% of the
zero-result case's inclusive CPU samples, identifying candidate decoding as a
future investigation rather than another change in this attempt.

Three main/Go paired timing runs complete from frozen source commit
`8677aff8f9b7a5e575879b698546c38d7af4a48c`; the incremental bundle preserves that
measurement commit before the evidence amend. All six runtimes terminate with the
original gate exit 1, and all three pair verifications pass. Every case, canonical
success, original failure and both diagnostic prewarm ledgers are retained.
Builds, tests and profiling do not overlap these timed invocations.

| State | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Candidate Go slower than main |
| --- | ---: | ---: | ---: | ---: |
| cold | 74.301 | 56.654 | 26.836 | 937 / 1266 |
| startup-prepared | 73.715 | 56.084 | 24.659 | 926 / 1266 |
| warm-after-failed-prewarm (diagnostic) | 67.763 | 50.492 | 19.443 | 1180 / 1266 |

Candidate Go regex-or-zero is about 10.88–10.92 s, and regex-or-targeted about
3.45–3.47 s across states. Paired main observations are 12.59–14.40 s and
4.09–4.50 s respectively. These are n=1 point observations, not per-case P95 or
statistical regression estimates. The prior Go campaign precedes the independent
quote semantic correction; all old/new observations and exact source identities
remain visible. Main's original resource counters are retained; Go diagnostic
counter observations above are separate from formal sampled resource accounting.

Keep the matcher plan: full observed correctness and graph-state parity are
preserved, and each timing state shows lower slow-regex and aggregate query
clock observations, consistent with removal of general matcher/decoder work.
All 3,801 per-state/case summary rows remain insufficient; summary exit 1 preserves
unproven acceptance. The full 10x target, required benchmark gates, formal warm,
resource/work accounting and server fidelity remain open. Commands, environment,
source hashes, complete captures and limitations are in
`docs/go-server-baseline/native-regex-quoted-contains/`.

### 2026-09-08 — Attempt 24: reuse fused generic SID filtering

Hypothesis: the six-term wrapped DISTINCT OR path needlessly fully decodes every
Enum/Local/Field node and repeats lowercase matching for identical string IDs.
Main filters raw SIDs with per-concrete-iterator match arrays, then lazily decodes
and merges only matching nodes. Baseline Go is `513e2b96`; pinned main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. Candidate source is frozen in the
complete `native-generic-string-disjunction/candidate-replay/` archives. The
measured revision is `acb065745f123d1dc362e24574628e40a05b4e91`, preserved by an
incremental source bundle before its evidence amend.

Reuse the existing raw SID accessor and lazy provider, exclude CallSite before
creating its lookup, share memo states by Java transform/mode/expected identity,
and derive order from yielded payload IDs. Annotation retains full decoding.
Do not allocate memo arrays for empty concrete types. The general node decoder,
worker scheduler and separate scalar-reader hypothesis are unchanged.

Two actual Java captures contain 181 public scenarios and20 private-provider
controls. Baseline matches157 public and19 provider controls; both candidate
captures match166 public and19 controls. All nine corrected public cases concern
raw-filter skip/error ordering. All16 remaining differences stay in the archive,
including malformed decoding/loading, surrogate materialization, fallback order,
and one explicit provider-wrapper API scope difference. The bounded corpus has
no newly mismatching case; it does not prove full server parity.

83 focused Java-observed regression cases pass, plus full-module race tests and
vet with2,539 recorded inputs unchanged. Initial test-authoring failures and
corrections remain recorded. Full real64 cold replay uses the same64 real
persisted graphs,1,152 unchanged files and all1,267 original ordered cases.
Every public output agrees, including the original failed case821. Strict state
comparison nevertheless FAILS with15,162 mappedRangeCount differences; the
controller correctly stops before warm/startup preparation.

The first state difference occurs in case3's unchanged ordinary CallSite path,
before either changed function is involved. Inspection points to existing
ordered source-task cancellation and speculative cache publication. Predeclared
sequential old/new cold controls investigate it; failures must not be resampled
away or hidden by relaxing the comparator. A source-level explanation is not a
passing validation result.

Both predeclared sequential cold controls subsequently pass the unchanged strict
comparator: baseline and candidate each preserve all 1,267 public outcomes and
162,304 graph-state observations. Candidate warm prewarm and startup-prepared
also pass, giving 486,848 observations across the candidate's three successful
state comparisons. All five runtime captures and the first state failure are
retained. This supports matched behavior in the isolated captures; it does not
prove schedule-invariant speculative cache state under arbitrary contention.
The complete 2,535-file candidate module matches both frozen replay sources.

| Evidence | Current result |
| --- | --- |
| Focused public regression checks |83 passed |
| Complete adversarial Java comparison |166/181 public,19/20 scoped provider controls equal |
| Full module race tests and vet | Passed |
| First real64 cold public results |1,267/1,267 agree, original error retained |
| First real64 cold state comparison | Failed; mappedRangeCount differs |
| Sequential baseline/candidate cold controls | Both strict comparisons pass |
| Candidate warm prewarm/startup-prepared | Both strict comparisons pass |
| Paired latency observations | Three complete main/Go pairs, n=1 per case/state |
| Go CPU/allocation diagnostics | Both complete original workloads verified; exact brackets retained |
| Per-case P95 /10x acceptance | Unproven |
| Keep/revert decision | Keep; full target remains unproven |

All three controlled pairs complete and verify every original case/result/error;
six timed runtimes terminate with the original gate failure. Builds/tests end
before timing, and each runtime receives its own audited real64 clone. The
previous campaign's final Go revision is513e2b96, measured as8677aff8; its exact
source identity and all point observations remain bound in the comparison.

| State | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Candidate Go slower than main |
| --- | ---: | ---: | ---: | ---: |
| cold |56.654 |47.519 |27.065 |925 /1266 |
| startup-prepared |56.084 |46.956 |22.414 |935 /1266 |
| warm-after-failed-prewarm (diagnostic) |50.492 |41.551 |19.279 |1188 /1266 |

Wrapped six-term OR takes1.46–1.68 s in Go versus3.07–3.38 s previously and
0.053–0.072 s for paired main. Unwrapped six-term OR takes1.31–1.39 s versus
2.58–2.74 s previously. These are independent single observations, not per-case
P95 or statistical causal speedups. All3,801 state/case summary rows retain an
insufficient-sample result and the full10x goal remains unproven. Original main
resource sampling is archived; Go's equivalent formal resource/work metrics
remain missing. CPU/allocation profiles run separately only after timing ends.

Both separate diagnostic runtimes complete with identical instrumentation and
the entire original workload, no additional GC or warmup, unchanged source and
fixtures, all canonical outcomes and the original gate preserved. The exact
process counter brackets, including runtime/profiler activity, are:

| Case | Before TotalAlloc (bytes) | Candidate TotalAlloc (bytes) | Before process CPU (s) | Candidate process CPU (s) |
| --- | ---: | ---: | ---: | ---: |
| global-six-or-zero |1,512,904,392 |83,735,392 |17.680708 |6.022078 |
| global-six-or-targeted |1,785,231,040 |376,804,616 |13.565079 |6.676716 |
| global-six-or-dense |1,711,444,576 |294,379,144 |14.214895 |6.396731 |
| global-six-or-wrapped-zero |1,632,995,480 |163,166,408 |18.012802 |7.344588 |
| global-six-or-wrapped-targeted |1,882,581,256 |421,672,936 |19.750954 |11.768850 |
| global-six-or-wrapped-dense |1,833,654,184 |379,019,200 |24.346378 |7.535464 |

CPU sample mass strongly disagrees with Rusage: after zero records73.16 sampled
seconds but6.022078 process CPU seconds inside the counter bracket. Profile and
counter boundaries differ; runtime wait frames dominate. Preserve this anomaly
without claiming a system/runtime bug or using sampled percentages as precise
CPU shares. Sampled allocation deltas can lag GC; stack groups overlap and zero
GC samples do not prove no GC. These clocks remain excluded from P95 acceptance.

Keep the fused generic route: nine observed fidelity differences are corrected,
complete isolated real64 results/state are preserved, and all six relevant
queries show lower paired point clocks and lower diagnostic allocation/CPU.
Retain the first state failure under concurrent correctness activity and every
remaining malformed/source/context/ordering gap. This does not establish the
full performance or server goal; no acceptance requirement is waived.

The full goal and required benchmark gates remain open. Exact commands, source
archives, raw outcomes and limitations are under
`docs/go-server-baseline/native-generic-string-disjunction/`.

### 2026-09-08 — Functional follow-up: bound generic DISTINCT provenance consumption

Baseline Go is `6d061b525a91d844f5c22a7231af6e18c61c0c3a`; original main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. Main stops generic provenance scanning
when the cumulative CallSite plus generic hit set covers the selected tuples,
checking after every consumed candidate. Go previously collected generic hits
separately up to the projection limit and could reach a corrupt suffix that
main never reads. A separate generic hit probe now implements the original
boundary; ordinary prefix projection keeps its existing DISTINCT limit.

Two independent actual-JVM captures establish 13 public cases on small persisted
correctness graphs with 40 sources. The baseline matches 10; the candidate
matches all 13, including three required atomic failures. Three former failures
become the exact main success responses. A test-only Go overlay supplies the
exact baseline production file, and complete outputs are retained. Full-module
race tests and vet pass with unchanged inputs. An independent source/test
review finds no issue within this consumption-boundary scope.

The existing full 201-case oracle has no compared result/error/state changes:
166/181 public cases and 19/20 scoped provider controls agree with each main
reference; all 16 known mismatches remain visible. Initial oracle construction
also records main's capability-unavailable error when the first graph has only
generic nodes. Those 13 original controls are retained separately: baseline
matches 4 and candidate 7, leaving six pre-existing capability-boundary gaps.
Adding a nonmatching CallSite in the final prefix fixtures reaches the intended
phase; it does not waive the earlier observations or redefine their validity.

| Evidence | Result |
| --- | --- |
| New public prefix cases | 10/13 baseline; 13/13 candidate |
| Full module race / vet | Passed |
| Complete previous 201-case matrix | No new compared differences; 16 remain |
| Initial capability-absence cases | Six mismatches remain |
| Real64 cold | 1,267 cases; 162,304 state observations align |
| Real64 warm prewarm | 1,267 cases; 162,240 state observations align |
| Real64 startup-prepared | 1,267 cases; 162,304 state observations align |
| New latency / CPU / memory measurements | None; correctness correction only |
| Per-case P95 / 10x acceptance | Unproven |
| Keep/revert | Keep the proven consumption-boundary correction |

All real64 runtimes execute serially on fresh clones after correctness checks and
JVM captures terminate. The same 64 real graphs, 1,152 unchanged files and every
original testcase are retained. The original case821 failure and all-success
gate exit 1 remain; formal warm only reaches its failed prewarm boundary. Prior
contended cache-state differences are not erased by the new isolated matches.
There is no new performance claim, and earlier n=1 timings are not measurements
of this changed engine. Full fidelity, equivalent resource/work accounting,
required benchmark gates and the 10x per-case P95 goal remain open.

Exact commands, runtime/source identities, raw outputs, retained failures and
verification receipts are under
`docs/go-server-baseline/native-generic-provenance-prefix/`.

### 2026-09-08 — Functional follow-up: preserve missing DISTINCT projection capability

Baseline Go is `2e75f69e19ee4660a9187ac9af719bf7cd567c6a`; original main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. The previous investigation retained
six cases where a generic-only first graph should fail because its CallSite
projection capability is unavailable. Go incorrectly supplied an available empty
index before choosing the storage policy.

The store now distinguishes an empty serial raw scan from an unavailable
structural index, after evaluating prepared-file presence. A regular but invalid
index file still selects serial storage for one-source DISTINCT, matching main.
Initial projection maps unavailable to the exact IllegalStateException and
message; provenance accepts no raw hits and continues generic candidates. Four
ordinary candidate branches tolerate missing indexes. Existing filtered-count
zero guards and index-lifecycle unavailable handling remain valid.

Two independently repeated actual-JVM matrices add 46 primary and eight
supplemental cases. Baseline matches 30/46 and 6/8; candidate matches 46/46 and
8/8. The prior 13 initial controls improve from 7/13 to 13/13, closing all six
recorded capability mismatches. Complete ordered results/provenance and atomic
errors are asserted. Baseline testing overlays all three production files with
exact committed bytes; no partial baseline is used. An initial test-harness
Single/Cross selection mistake and its three metadata differences are preserved,
then corrected to invoke the same executor as the original JVM.

Full module race tests and vet pass with 2,552 recorded inputs unchanged.
Independent source/test review identifies no missed production dependency in
this boundary. The existing complete 201-case oracle is unchanged in compared
public results/errors/state: 166/181 public and 19/20 scoped provider controls
match each original reference, leaving the same 16 retained mismatches. Its
comparison remains failed, rather than turning known errors into passing cases.

| Evidence | Result |
| --- | --- |
| Primary capability matrix | 30/46 baseline; 46/46 candidate |
| Invalid file / ordinary supplement | 6/8 baseline; 8/8 candidate |
| Previously retained initial controls | 7/13 baseline; 13/13 candidate |
| Full module race / vet | Passed |
| Existing 201-case matrix | No new compared differences; 16 remain |
| Real64 cold | 1,267 cases and 162,304 state observations align |
| Real64 warm prewarm | 1,267 cases and 162,240 state observations align |
| Real64 startup-prepared | 1,267 cases and 162,304 state observations align |
| New latency / CPU / memory measurements | None; correctness correction only |
| Per-case P95 / 10x acceptance | Unproven |
| Keep/revert | Keep the observed capability-boundary correction |

Every real64 runtime executes serially after module checks and JVM captures
terminate, on its own fresh clone of the same 64 real graphs with all 1,152
original files unchanged. Every original testcase and the original case821
failure remain; formal warm still only completes its failed prewarm. Prior
contended cache-state differences remain in their original archive. The new
JVM primary matrix also retains a workUnitsConsumed difference of 3,089 versus
3,361 for one 40-source query despite identical public output and index state;
this does not establish deterministic worker diagnostics or Go accounting parity.

No new performance claim follows from these correctness captures. Exact commands,
source identities, complete outputs and retained differences are under
`docs/go-server-baseline/native-distinct-capability-boundary/`. Full server
fidelity, resource/work accounting, required benchmark gates and 10x per-case
P95 remain unproven.


### 2026-09-08 — Functional follow-up: request work context and explicit row bounds

Baseline Go is `2547ef3d8c2c003ec8b66d3d00b7039e4a0bdd1e`; actual main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. Matching all original real64 case
definitions and public outcomes did not establish equivalent execution conditions.
This follow-up adds the original request-scoped work tracker and cancellation
contract, then wires the verified generic scan and literal-ID seek boundaries.
It does not claim complete finite-budget coverage or a performance improvement.

One context shares its atomic remaining budget and first cancellation reason
across sequential calls and UNION branches. Budget-only calls reset on every
execution. Negative units fail before cancellation, exact consumption succeeds,
and an overlarge batch saturates remaining work at zero before failing. Ordinary
mapped iteration decodes/skips absent offsets before charging; literal seeks
charge before lookup. Graph-free evaluation and cancellation checks consume no
work. Child cancellation reaches storage but does not poison a subsequent call.

Actual-JVM controls exposed why a public row bound cannot just truncate returned
rows: it changes scan work and UNION continuation. Explicit maxRows APIs now
preserve constructor, bound-validation, parsing and cancellation order, append
or tighten the last LIMIT, and insert an independent bound before a dynamic
LIMIT. UNION ALL stops by remaining capacity; UNION DISTINCT continues later
bounded branches for retained provenance. Existing legacy native limit callers
retain their previous behavior and have not yet been migrated in the server.

An ordered-property route uses main's admission and stable bounded heap, rather
than recording a generic sort as a fast path. Successful node-only streaming
pagination now records its fast completion. A first complete context check
retained one omitted streaming-fast diagnostic; the corrected frozen rerun
includes the same scenario. The prior HTTP42 audit now has a dated-state
qualification: its missing-testcase conclusion no longer describes the exact
1,267-case native manifest, while its execution-condition warnings remain valid.

| Evidence | Result |
| --- | --- |
| Main context/tracker oracle | 51 scenarios, 129 ordered operations match |
| Main literal-ID seek oracle | 14 scenarios, 21 operations match |
| Main bounded-execution oracle | 19 scenarios, 19 operations match |
| Main ordered-property oracle | 25 scenarios, 25 operations match |
| Main constructor/error-priority oracle | 10 observations match |
| Full module race / vet | Passed; 2,583 recorded inputs unchanged |
| Existing complete 201-case matrix | No new compared differences; 16 remain |
| Real64 cold | 1,267 cases; 162,304 graph-state observations align |
| Real64 warm prewarm | 1,267 cases; 162,240 graph-state observations align |
| Real64 startup-prepared | 1,267 cases; 162,304 graph-state observations align |
| New latency / CPU / allocation measurements | None; functional correction only |
| Per-case P95 / 10x acceptance | Unproven |
| Keep/revert | Keep the verified context and execution-boundary implementation |

Each JVM supplement was captured twice. All deterministic records repeat exactly;
the original four-worker private-tracker control retains differing per-worker
shares with the same total 100 consumes and four budget failures. Go tests compare
public rows, atomic failures, exact simple exception classes/nullable messages,
all eight available counters, remaining budget and cancellation state/identity.
JVM-specific stack/class/getter fields remain in the raw oracle without invented
Go values. Source and control-flow review independently checked the new paths.

The complete 201-case replay still matches 166/181 public cases and 19/20 scoped
provider controls. Its adapter does not pass the new ExecutionContext or measure
its diagnostics; raw legacy wording about the missing API is retained with that
qualification. The first matrix preparation selected a PATH `cp` lacking `-c`;
the dependent helper-copy failure occurred before any query ran. That attempt is
retained, and fresh v2 paths with `/bin/cp` produced the terminal complete replay.

All real64 runtimes run serially on independent audited clones after module and
JVM checks end, preserving all 1,152 original files. The original failed testcase
and all-success gate exit 1 remain; warm stops at its failed prewarm boundary.
The combined 486,848 graph-state comparisons agree. Prior contended cache-state
observations remain historical evidence, not a claim of schedule invariance.

Complete source identity checks bind all 2,542 module files to the module checks,
frozen real64 module and 201-case module before its diagnostic helper. Evidence
is under `docs/go-server-baseline/native-work-context/`. Remaining work includes
raw/indexed storage batch consumers and their error priority, other planner and
path counters, server request context/explicit-bound integration, resource
sampling, existing semantic differences and required benchmark gates. Earlier
n=1 timings do not describe this changed engine. Full server fidelity and 10x
per-case P95 acceptance remain open.


### Functional follow-up — serial raw work batches and empty-query dispatch

Baseline Go is `cf920cd4e84eafe7d3c641fe747bb0b1d14da16a`; actual main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a correctness follow-up,
not a new latency optimization attempt. The hypothesis is that raw serial
storage must submit pending work at main's exact batch/yield/finally boundaries,
including the ordering between budget errors and malformed persisted reads.

The native buffer now submits 1,024 inspected nodes at a time, clears pending
before submission, flushes before exposing a node, and settles pending work on
exhaustion or decoding failure. Empty flushes do not poll cancellation. Generic
Annotation/eager fallback charges after successful decoding before filtering.
Successful ordinary/lazy filtered dispatches now report their actual planner
counter after completion. Actual stacks established that even the tested
single-predicate Local name query uses the fused buffered disjunction path.

Independent review found that Method LIMIT 0 must precede the generic empty
filtered guard. A dedicated non-scanning admission helper preserves numeric-only
counts, inline-property validation, requested-zero ordering and the empty source
wave. Declined Method requests keep general evaluation. The initial real64
capture was stopped after cold while this was corrected; its partial warm output
and intermediate passing checks remain preserved. Final checks and replays use
new frozen sources and new output directories.

| Evidence | Result |
| --- | --- |
| New actual main serial-raw public oracle | 43 scenarios / 57 ordered operations match |
| New actual main Method-empty oracle | 16 scenarios / 16 operations match |
| Applicable actual buffer primitive oracle | 8 batch/null scenarios match |
| Existing actual JVM context/execution oracle | All 119 scenarios still pass |
| Native budget/cancel and empty-source controls | Pass |
| Full module race / vet | Pass; 2,775 recorded inputs unchanged |
| Existing complete 201-case matrix | No new differences; original 16 remain |
| Real64 cold | All 1,267 cases compared; 162,304 state observations agree |
| Real64 warm prewarm | All 1,267 cases compared; 162,240 state observations agree |
| Real64 startup-prepared | All 1,267 cases compared; 162,304 state observations agree |
| New latency / CPU / allocation measurements | None |
| Per-case P95 / 10x acceptance | Unproven |
| Keep/revert | Keep the verified serial accounting and dispatch correction |

The raw-work JVM oracle uses 20 actual-main-written small persisted correctness
variants, never performance fixtures. Two original-config CallSite failures have
variable nullable messages and omitted stacks; both observed outcomes remain in
the tests. Two separately flagged diagnostic captures repeat completely but do
not replace original JVM acceptance. The Method oracle uses a graph with no
methods and establishes admission/counter behavior, not nonempty method scans.
The public tests compare complete results/errors, all eight request diagnostics,
remaining work/cancellation and four mapped storage states. The four states are
zero/false in this oracle, so they prove no publication, not cache growth. Three
unimplemented storage event counters and JVM-only observers remain explicit.

The real64 fixture is the same immutable 64 persisted graphs, 1,152 original
files, and original 1,267-case workload. All three isolated captures preserve the
original case 821 failure and original gate exit 1; formal warm remains
unprepared. The 201-case adapter retains 166/181 public and 19/20 provider matches;
it does not supply the new context or compare its diagnostics. Its historical
API-unavailable wording is qualified in the accompanying native README.

Evidence under `docs/go-server-baseline/native-raw-work-batches/` binds all 2,545
module files across checks, real64 and the 201-case source copy. Remaining work
includes raw leading projection probes/caches, parallel and indexed consumers,
Method/path work, source-selection diagnostics, request-wide server integration,
resource sampling, known semantic gaps and required benchmark gates. Earlier
single-sample timings are not attributed to this changed implementation. The
full server fidelity and per-case 10x P95 goal remains open.


### Functional follow-up — leading raw projection and cache work accounting

Baseline Go is `5cf282278c132c2bc987d217a9453ccfafc32b44`; actual main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a functional accounting
correction, not a latency optimization experiment. The hypothesis is that raw
leading probes and cache hits must charge the same work at the same read and
publication boundaries as main, including failures that leave a completed cache.

Cold probes now charge inspected CallSites before reading their string IDs and
flush on return or error using the existing buffer. Completed probes publish
their cached IDs before the final flush, so budget failure can preserve a cache
that a later request reuses. Hits charge at least one unit before projecting
cached IDs. Shared OR/IN compilation now deduplicates complete filters in first
encounter order with Java UTF-16 equality, matching actual main cache identity.

| Evidence | Result |
| --- | --- |
| New actual main raw-leading public oracle | 26 scenarios / 52 ordered operations match |
| New compiler identity/order controls | All 8 pass |
| Existing request-context and serial-raw oracles | Pass in focused and full module checks |
| Full module race / vet | Pass; 2,913 recorded inputs unchanged |
| Real64 cold | All 1,267 cases compared; 162,304 state observations agree |
| Real64 warm prewarm | All 1,267 cases compared; 162,240 state observations agree |
| Real64 startup-prepared | All 1,267 cases compared; 162,304 state observations agree |
| Existing complete 201-case matrix | No new compared differences; original 16 remain |
| New latency / CPU / allocation measurements | None |
| Per-case P95 / 10x acceptance | Unproven |
| Keep/revert | Keep the verified raw-leading accounting and filter identity correction |

The actual JVM target uses 13 small persisted correctness variants, 208 files,
and 39 or 40 independent mapped source stores per scenario. Context replacement
preserves source objects; a one-node public prelude exhausts budgets through
normal execution. The 26 cases contain 37 executions, 11 context replacements
and four prelude executions. Both original JVM captures' execution records match
completely, including nullable errors/stacks and execution observations. Fixture
identity differences from writer timestamps remain recorded separately. These
synthetic controls are not performance evidence.

The writer initially generated 11 persisted string-index sidecars. Both original
prepared captures (24 cases / 48 operations) remain intact. The target explicitly
removes those sidecars with exact mutation hashes to exercise serial fallback.
Six prepared cases instead enter PersistentIndexReadWork and have different
outcomes/accounting; native parity for that configuration remains unresolved.
The corrected target never substitutes for those prepared observations.

The first native focused run failed only on 16 parent final-state comparisons:
all 52 operation subtests passed, but the harness compared live Go caches with
main's closed stores. The corrected harness actually closes every store, asserts
successful Close and public observer ErrStoreClosed, compares post-close request
state, and retains complete repeated JVM private final observations. It does not
invent post-close Go cache values. Existing store tests independently verify
cache clearing and concurrent Close. Both failed and corrected checks are kept.

The full real64 regression uses the same immutable 64 graphs, 1,152 original
persisted files and byte-identical 1,267-case workload. All three serial captures
retain original case 821 and gate exit 1; formal warm remains unprepared. The
486,848 graph-state observations agree. JVM fully qualified error-class parity
and schedule invariance are not established. The 201-case adapter still matches
166/181 public and 19/20 provider controls and does not pass the new request
context or compare its diagnostics; its historical API-unavailable wording is
qualified in the native README.

Evidence is under `docs/go-server-baseline/native-leading-work-accounting/`.
Source verification binds all 2,546 module files across checks, frozen real64 and
the complete matrix before its helper. Persisted/indexed/parallel/Method/path
work, cancellation-dependent cache history, server context integration, resource
sampling, existing semantic gaps and required benchmark gates remain unfinished.
Earlier n=1 timings do not describe this changed module. Full function fidelity
and at least 200 valid observations per case/runtime/state proving 10x P95 remain
the acceptance criteria, not claims made by this correctness follow-up.


### Functional work in progress — persisted index work and mapped cursor protocol

Baseline Go remains `f0838dda4f0d67628d028826d0577a8151765288`; actual main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. This is an unfinished functional
correction, not a performance experiment or an accepted replacement for the
previous real64-correct baseline. No latency, CPU, allocation or P95 measurements
were made for these changes.

The current work adds actual persisted-reader, mapped-reader, two-pass build and
trigram preparation accounting, including callback failure identity, retries,
cache publication and Close coordination. Four independent actual-main public
oracle groups cover 111 scenarios and 482 ordered operations. Their full outcomes,
request diagnostics and supported live cache state match; all fixture bytes and
30 actual sidecar rewrites are checked against the original captures. Unsupported
JVM private/resource observers remain explicit.

The first full64 cold replay and an identical-source repeat both preserved all
1,267 public query outcomes but differed on 15,162 mappedRangeCount observations.
A fresh actual-main replay exactly reproduced the original 1,267 outcomes and
162,304 graph-state observations. These differences therefore remain failures;
the comparator and reference were not relaxed or replaced.

A source audit then found excess Go local cancellation checks and eager warm
posting reads. Main-only mapped span/cursor accessors now use actual absolute
1,024-position checks, binary-search and range-finally work, cold owned orders,
and lazy warm cursor advancement. Duplicate heap entries consume work, yielding
flushes it, and retained fallback uses the retained matcher. A new actual-main
storage-method oracle repeats all fields across 11 controls / 19 operations.
These controls are not public-query cases, and not all 11 yet have equivalent Go
runtime controls; `checks/cursor-protocol-audit` records the coverage boundary.

| Current evidence | Result |
| --- | --- |
| Public persisted/mapped/build/trigram oracles | All 111 scenarios / 482 operations pass again |
| Mapped cursor/string focused race controls | Pass, including 28 Unicode cases and 8 real pipeline cancellation controls |
| Full module context / race / vet after test migrations | Pass; 3,300 recorded inputs unchanged |
| Latest real64 cold after cursor correction | All 1,267 outcomes agree, but the same 15,162 mappedRangeCount differences remain |
| Latest real64 warm / startup-prepared | Not executed after the cold comparison failed |
| Latest complete 201-case matrix | Not rerun for this source |
| New P95 / 10x acceptance evidence | None |
| Keep/revert decision | Work remains unaccepted and under investigation; do not claim real64 parity |

The latest cold difference still begins at `replay/3/after`, graph
`fixture-android-02`, main count 7 versus Go 0. All 1,152 original persisted graph
files remain unchanged. Original case 821 and its all-success gate failure remain
in the full workload. The native workload is byte-identical to both full main
exports; the fresh main replay's case-array output also matches all definitions.

Failed focused and full checks are retained alongside corrected runs. Two new
fixture tests initially manipulated the wrong backing data or selected the wrong
index representation. A separate old test observed the no-longer-used generic
order accessor, so its prescribed cancellation never occurred. Corrections are
test-only and independently audited; production was not changed to restore those
incorrect assumptions. Evidence and exact source bindings are under
`docs/go-server-baseline/native-persisted-work-accounting/checks/`.

The mapped protocol correction has not resolved the full64 state regression.
Next diagnosis separates each affected graph's mapped candidate behavior from
multi-source task cancellation, using new real-data copies. Optional captured
index publication, resource reservations, other query/server functionality,
known semantic gaps and required benchmark gates also remain unfinished. The
full objective stays 100% functionality and per-case/per-state P95 at most one
tenth of main, with at least 200 valid samples; this entry does not redefine it.


A subsequent private-iterator diagnostic completed on fresh reflink copies of
`fixture-android-02` through `fixture-android-07`. All seven observed index fields
matched their full64 case-3 starting state. With sourceCount 64 and the same
CONTAINS/java predicate, but no multi-source cancellation, all six iterators
completed and created mappedRangeCount values 7, 6, 7, 1, 5 and 5, matching main's
observed counts. Complete returned IDs/node digests and selected fixture hashes
are recorded under `checks/cursor-real64-diagnostic/`. This narrows the next
investigation to the multi-source execution path; it does not reproduce full
query history or prove the cause. Fixed-worker scheduling and cancellation are
being compared next. No acceptance or performance claim changes.

### Functional follow-up — prepared fixed-worker scheduling

The baseline pins remain Go `f0838dda4f0d67628d028826d0577a8151765288`
and main `4e328b0109e13c896b74004823fb049fcb19251a`. On the same immutable
64-graph / 1,152-file real dataset, the candidate now selects fixed workers only
after the leading source is insufficient and every source meets main's prepared
wide-scan gate. Workers are reused across suffix sources, replenish tasks after
ordered consumption, and preserve distinct normal-completion and cancellation
behavior. A review found queued canceled futures retained closures and worker
references; both references are now released under the future's lifecycle lock.
The separate mapped-entry cancellation gaps have not been changed in this run.

The frozen candidate contains 2,568 module files. Focused race controls, all
111 public oracle scenarios / 482 operations, and full module context / race /
vet checks pass; the latter verify 3,388 recorded inputs remain unchanged.
The subsequent real64-v3 controller terminates with exit 0 and all three
comparison verifiers exit 0. Public differences and supported graph-state
differences are empty in every executed phase, resolving the previously retained
15,162 mappedRangeCount differences. Cold and startup-prepared each cover 1,267
replay cases and 162,304 graph-state observations. Warm covers only the 1,267-case
warmup and 162,240 observations: the original case-821 error prevents preparation
and no formal warm replay executes. All 1,152 original graph files remain
unchanged for each runtime.

These successful comparisons do not mean the all-success gate passes. Main and
Go retain the original case-821 IllegalStateException and runtime exit 1. Go's
unavailable JVM fully-qualified errorClass observer is still reported separately.
The comparator and original references are unchanged. Exact evidence is retained
under `native-persisted-work-accounting/checks/scheduler-real64/`.

A fresh 201-case supplementary replay also completes. It matches 166/181 public
cases and 19/20 private-provider controls against both actual-main captures;
all 16 existing differences remain and no compared outcome differs from the
previous candidate. Source and original fixture files are unchanged. The failing
comparison, complete outputs and source archive are retained under
`native-persisted-work-accounting/checks/matrix201/`.

This remains an unfinished functional correction. Retain the scheduling change
for continued verification; do not report full functional acceptance. There are
no latency, CPU, memory or allocation measurements for this candidate, and no
new P95 evidence. The target remains per-case/per-state Go P95 at most main/10,
with at least 200 valid observations and all required functional and benchmark
gates; changing implementation language alone does not establish that result.

### Functional follow-up — main storage entry and cancellation

The revision pins remain Go `f0838dda4f0d67628d028826d0577a8151765288`
and main `4e328b0109e13c896b74004823fb049fcb19251a`. This correction changes
14 production files relative to the preceding frozen fixed-worker candidate;
the fixed-worker implementation and its ordinary dispatcher are unchanged.
It is unfinished functional work, with no latency/CPU/memory/allocation or P95
measurement and no acceptance claim.

Actual-main storage-entry captures E01–E10 revealed that Go polled worker
cancellation before cached metadata and before the cold persisted identity
charge. The frozen Go baseline matched only E05/E06, retaining 70 differences.
Seven no-action controls were identical with and without a test-only work
observer. The observer records real tracker calls, never adds work, preserves
the thrown object, and can be removed to restore the original tracker file
byte-for-byte. Its initial overlay-vet failure remains archived. This is a
storage-node adapter; full Java node shape, private lookup counters, JVM stacks
and other unavailable observers remain explicit rather than synthesized.

The main entry now uses lifetime-checked metadata/cache/decoder primitives while
retaining the worker context at actual loader and scan checkpoints. Cold mapped
entry reads the real identity before the loader's first worker check; legacy
semantic identity retains its real work/cache before raw identity scanning.
Current regular-file admission precedes a cached mapped-view return. Retained
Split entry first checks every predicate's eligibility, then consults actual
matching-string caches in predicate order; an all-empty match skips the node
cache's minimum charge while preserving other generic node-type children.
Cached-node iteration, periodic retained heap checks, mapped interruption
class/message and canonical-order reads are aligned with their main paths.
Generic/strict Store wrappers keep their existing context-bearing checks.

| Evidence for entry correction | Result |
| --- | --- |
| Actual-main storage entry E01–E10 | Both candidate captures have zero compared differences; seven plain controls also match |
| Public persisted/mapped/build/trigram oracles | All 111 scenarios / 482 operations pass |
| Focused entry/lifetime/cancellation race checks | Pass on the current 2,575-file module |
| Full context / module race / vet | Pass; all 3,395 recorded inputs unchanged |
| Current real64 cold, v4 | 1,267 public outcomes agree; 15,162 mappedRangeCount differences remain |
| Current real64 warm / startup-prepared | Not executed after cold comparison failed |
| Current 201-case matrix | Not rerun for this source; previous candidate's 16 differences are not a new-source result |
| P95 / 10x evidence | None |
| Keep/revert decision | Keep under investigation; the entry fix is not an accepted full64 replacement |

The new real64 difference starts at `replay/3/after`, `fixture-android-03`, main
range count 6 versus Go 0. Differences are confined to Android graphs 03–08;
all their mapped views already existed before this query. Case-3 after counts
for graphs 01–08 are main/previous candidate `[4,7,6,7,1,5,5,4]`, current entry
candidate `[4,7,0,0,0,0,0,0]`. This is a range-validation publication difference,
not evidence that the mapped views failed to initialize. Every original one of
the 1,152 real graph files remains unchanged. Case 821, its original error and
the original all-success gate failure remain preserved.

A new cold run on a fresh copy of the preceding 2,568-file frozen source again
matches all 1,267 outcomes and 162,304 graph-state observations. It does not use
the changed workspace source. Thus the current failure is not dismissed as a
bad reference or a generally failing previous candidate. The existing Go
executor prestarts its full goroutine capacity, whereas main's executor creates
core workers as first tasks are submitted; other main paths also share the
executor. These are verified source differences, but there is no observation
yet proving whether the pool is cold at case 3 or that this explains the cache
difference. No task-start barrier, forced cache completion or comparison
relaxation has been introduced.

Failed focused and full checks are retained. The first focused failure included
one nil-versus-empty test assertion and earlier every-yield cancellation
assumptions; the same production source passed E01–E10 before those tests were
corrected. The first full check found two more tests locating old function names
after a shared-reader extraction. Only those two names changed, preserving
cancellation and worker-join assertions. The subsequent focused and complete
checks pass. Exact source bindings, test-only deltas, complete raw entry
captures, failed v4 cold capture and successful old-source repeat are recorded
under `native-persisted-work-accounting/checks/`, including
`entry-source-audit.json` and `entry-scheduling-review.md`.

Next work must distinguish actual executor admission/start, storage entry and
worker-interruption timing before choosing a scheduling correction. Public
semantic gaps, remaining query/server/resource functionality and all required
performance gates remain open. The full goal remains 100% functionality and
per-case/per-state Go P95 at most main/10, using at least 200 valid observations.

### Functional follow-up — lazy executor core creation

The Go base remains `f0838dda4f0d67628d028826d0577a8151765288` and the main
reference remains `4e328b0109e13c896b74004823fb049fcb19251a`. Relative to the
preceding frozen entry candidate, only `main_fixed_workers.go` and its test
file change. The complete module still contains 2,575 files. This is a
source-contract correction, not a measured performance optimization.

The executor now starts with zero cores. Each submission below core capacity
creates one persistent worker with that future as its first task, even when a
previous core is available; submissions after capacity enter the existing FIFO.
The worker releases its first future and then consumes queued futures. This
matches the inspected JDK17 `ThreadPoolExecutor.execute`/`runWorker` mechanism.
No startup barrier, delay, forced range publication, extra cancellation poll,
or comparator change is introduced. Other native runners still use their
separate goroutines; cross-path executor sharing remains unfinished.

The initial focused build failed because the new capacity/core fields were
missing from the struct. That exact source and log remain archived; adding the
fields changed no test assertion. The corrected 14 worker tests pass three
times under race detection. The broader frozen entry, cancellation, prepared
scan and lifetime checks pass, as do full context, module race and vet checks
over 3,395 unchanged inputs. Independent review finds no new correctness or
lifetime blocker; its small close-test observation boundary is recorded rather
than treated as proof of an intermediate scheduling state.

| Evidence for lazy executor correction | Result |
| --- | --- |
| Current real64 cold, fresh clone | 1,267 public outcomes agree; 2,527 mappedRangeCount differences |
| Compared graph-state observations | 162,304; original 1,152 graph files unchanged |
| First remaining state difference | `replay/3/after`, android-08, main 4 / Go 0 |
| Warm / startup-prepared | Not executed after cold comparison failed |
| Fresh supplementary matrix | 166/181 public and 19/20 provider controls match; all 16 earlier differences remain |
| Matrix change from previous capture | No compared outcome changes across all 201 controls |
| P95 / latency / CPU / memory / allocations | Not measured |
| Keep/revert decision | Retain source-faithful correction for continued work; full functional acceptance remains unproven |

Case-3 range counts on android01–08 are now `[4,7,6,7,1,5,5,0]`, compared with
main/old frozen scheduler `[4,7,6,7,1,5,5,4]` and the preceding entry revision
`[4,7,0,0,0,0,0,0]`. Thus this capture has fewer state differences, but still
fails the original comparator. The actual replay runtime preserves case821's
original error and exit1. Do not infer that pool creation is the cause merely
from these counts, or that the unobserved suffix has equivalent scheduling.

The exact source archives, original failed build, successful focused/full
checks, complete failed cold replay and 201-case capture are retained under
`native-persisted-work-accounting/checks/lazy-core-*`. The source-binding audit
checks the complete frozen module against the executed tests and cold replay;
it explicitly preserves failed and unexecuted gates. The next diagnostic must
record actual executor history and source/checkpoint events through case3.
There is still no evidence establishing the requested per-case/per-state
10x P95 improvement or complete server/query/resource parity.

A subsequent read-only review of `six-empty-1` and `six-empty-2` corrects the
earlier assumption that their mismatch proves a coalesce/UTF16-value bug. Each
has six rows. The original main capture and its own repeat already disagree
on row-array order; main, repeat and the current Go capture have identical full
`rowsUTF16` multisets, including nulls and provenance. Main and repeat differ
only in their `rows`/`rowsUTF16` arrays for these records. One deterministic Go
array cannot equal both reference arrays. The original 16 strict comparison
differences remain archived unchanged; no sort, hardcoded node-type order or
comparator exception has been added. Reference order instability needs separate
classification before selecting a production correction for these two cases.

### Diagnostic follow-up — executor history through real64 case 3

Two serialized diagnostic captures run on new copies of the exact old v3 and
entry-v4 frozen modules. Neither uses the new lazy-core candidate. Both retain
the original 1,267-case workload bytes and execute the explicitly labeled
prefix0–3 through the public replay CLI. Eight copied files receive additive
hooks and one bounded in-memory logger is added; the exact edits mechanically
reverse to the original text. The original modules, both instrumented copies,
and all 1,152 reference/copied graph files pass their respective postflight
checks. Both builds, prefix runtimes and controllers exit0; no trace events
are dropped or left unpublished. The event log is a diagnostic observation,
not a timing instrument or a full correctness/performance gate.

Both traced processes create their capacity-eight executor and start all eight
cores during query0. They reuse it in query1 and query3; no legacy runner event
is observed in this prefix. In v3 query3, one worker future processes all eight
admitted source indices before the coordinator consumes the first result; the
remaining seven futures are canceled before their bodies start. In v4 query3,
eight futures start and every source finishes before the first source-ordered
merge reaches LIMIT. Neither trace observes a rejected mapped anchor/range poll
in query3. Future identity must not be treated as graph identity: one future
can process several source tasks.

Both traced query3 after-states have android01–08 range counts
`[4,7,6,7,1,5,5,4]`. The v4 trace therefore does **not** reproduce the original
uninstrumented v4 missing-range failure. Its event calls, atomic buffer writes
and hooks inside existing locks perturb scheduling. The traces establish their
own warm-pool histories and source ownership; they do not establish the cause
of the uninstrumented failure, equivalent scheduling distributions, current
lazy-core cache parity, or any P95 improvement. Preserve the earlier failed
captures and comparator unchanged. A next diagnostic should reduce hot-path
event volume before trying to identify where the failing source is canceled.

### Diagnostic follow-up — sparse events on the frozen lazy-core source

The new sparse runner executes only the original real64 prefix0–3 on a fresh
copy of the frozen lazy-core candidate. Successful range, cache, publication
and posting/poll events are removed; existing cancellation checks still execute
exactly once, with only actual rejection recorded. The complete source edits,
original workload bytes, original fixture hashes and all failed/successful
observations remain separately identifiable from the earlier full traces.

The build, prefix runtime and controller exit0. Its 1,100 events have no dropped
or unpublished slots. Query0 creates all eight cores. At query3, one future
processes all eight admitted sources before the first source-ordered prefix
merge; seven other futures are canceled before starting. No recorded mapped
check rejects cancellation. All eight range counts again match main, so this
sparse run still does not reproduce the uninstrumented lazy-core android08
failure. It is not a replacement passing capture or proof of the failure's
cause. The untouched analyzer records the sole difference from the plain
lazy-core prefix: android08 has four ranges instead of zero. Actual artifacts
and analysis are in `checks/entry-scheduling-sparse-evidence/` and
`checks/entry-scheduling-sparse-audit/`.

### Functional follow-up — Enum argument-count consumption

Go base `f0838dda4f0d67628d028826d0577a8151765288` and main reference
`4e328b0109e13c896b74004823fb049fcb19251a` are unchanged. This candidate changes
one production file, `projection_node.go`, relative to the frozen lazy-core
source, adds its focused test and re-enables one existing public regression.
The complete module contains 2,576 files. No timing optimization is claimed.

Main's top-level Enum reader consumes `(0 until argCount)` sequentially. The
native projection reader previously used the strict decoder's early
count-versus-remaining validation, so `enum-bad-tail-hit` returned the wrong
error before reading the actual values. The projection path now reads the Enum
header and actual argument values in order until the first read error; negative
counts leave the tail unread. It does not allocate an array from an untrusted
count. Strict `Store.Node` and nested value/list decoding remain unchanged.

The original-source overlay reproduces the old count-guard failure. The
candidate's focused Store tests pass under race detection three times, including
both candidate APIs, actual Java-written variants, owned nested values after
Close, and zero/negative-count source-derived controls. The three public Enum
hit/miss/global-miss controls match the actual main captures; the hit returns
the real SID37/list21 `IndexOutOfBoundsException`. The regular generic oracle
selection increases from83 to84 without changing expected reference data.
Independent review finds no blocking defect in this scoped correction.

| Evidence for Enum correction | Result |
| --- | --- |
| Full context / module race / vet | All pass; 3,396 recorded inputs unchanged |
| Complete 201-case matrix | 167/181 public and 19/20 provider controls match; 15 strict differences remain |
| Change from preceding 201-case capture | Only `enum-bad-tail-hit` changes; its reference mismatch is removed |
| Real64 cold / startup-prepared | Each matches all 1,267 replay outcomes and 162,304 state observations |
| Real64 warm | Matches the 1,267-case warmup and 162,240 state observations; formal warm preparation still fails at the original gate |
| Original graph integrity | All 1,152 original files unchanged in each runtime |
| Original gate | Case821 and runtime exit1 preserved; all three comparison verifiers exit0 |
| Keep/revert decision | Retain the verified scoped correction; full functional parity remains unfinished |

The fresh state comparisons pass, but an Enum decoder correction does not by
itself establish the cause or elimination of the earlier scheduling-dependent
range observation. Previous failed cold captures and both non-reproducing
diagnostics remain intact. Two of the fifteen strict matrix differences retain
the separately documented unstable reference row order. Remaining query,
server/resource behavior and required benchmark gates are not declared complete.
Exact focused failures, source and full checks, changed-case comparisons and
complete real64 captures are archived under `checks/enum-*`.

### Measurement setup — manifested Enum candidate, 200 paired trials per state

The original committed-source measurement builder remains unchanged. A separate
`native64-p95-sampling/prepare-frozen.py` accepts an explicit frozen module and
the complete manifest from its correctness checks. It preserves the original
manifest bytes, binds both manifest hashes before compilation, checks the
original/copy afterwards, and identifies the engine by its complete source hash
rather than claiming the dirty candidate equals HEAD. Independent review found
an initial manifest-read/hash timing window; the implementation was corrected
to use the first-read bytes and saved hashes, with the review/fix retained.
Changed-hash, missing-path and extra-path controls all reject before building.

The actual build completes and preserves the existing main launcher, dependency,
compiler/JVM and timer checks. It binds all 2,576 module files and 1,427 build
inputs. The frozen source identity is
`7e9f6b88fa28a505270117472c340e4ccd171bcf27c82127186f5cc7ecf8cfd0`;
the measured native binary SHA256 is
`f65002ba89f6d8c3c6e73c679186a8570b3244e94b0acf02d8d627f952e42734`.
The workspace revision is recorded separately. Build output is
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-build-v1`.

After correctness runtimes and builds finish, the original serial sampling
controller starts a new 200-pairs-per-state campaign at
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-200-v1`.
It retains the original complete 1,267-case workload, alternates paired runtimes,
rotates state order and holds its host measurement lock. States are cold,
startup-prepared, and the explicitly diagnostic `warm-after-failed-prewarm`;
the latter is not a passing formal warm benchmark. Failed-query times, original
gate failures and all trials remain visible. No failed or partial trial is
silently replaced. The first cold main child is observed live at launch.

This is a running measurement campaign, not a completed P95 table or a 10x
claim. `measurementAcceptanceEligible=false` remains unchanged while original
gate failures and functional/server/resource gaps remain. The final per-case
report must still establish at least 200 valid observations and the specified
main-P95/Go-P95 threshold; required PR benchmark gates remain separate.

### Functional — Annotation top-level pair-count correction (prepared, untested)

On 2026-09-09, source and archived-oracle review isolates the three remaining
`annotation-bad-tail-*` differences. Main reads the signed top-level pair count
and consumes actual keys and values sequentially, failing at StringTable SID 28
of 21. The checked Go candidate instead rejects `2147483647` against the 104
remaining bytes before reading that key. Annotation is fully decoded before
filtering, so hit, miss and global-miss must all preserve the actual read error.

The workspace projection reader now has a separate Annotation branch with
sequential key/value consumption and insertion order. It leaves the strict
Store decoder and nested value contract unchanged. New Store test code covers
the original Java fixture variants and explicitly source-derived boundary
controls; the public regression selection expands from 84 to 87 original cases,
including the before/after retained-index difference of global-miss. All tests
remain unexecuted at this stage. The last verified 201-case result still has
15 strict differences; neither three resolved gaps nor full parity is claimed.

The serial 200-pair-per-state measurement campaign remains live. Its input map
contains no workspace Go module paths, and its native module/binary are the
independent frozen Enum build above. Those inputs are unchanged. Source edits
and small-file review/formatting proceed without starting another compiler,
test or replay; runtime verification is deferred until the campaign is
terminal. Any current P95 observations belong to the Enum candidate and must
not be attributed to this untested Annotation change. No new latency, CPU,
memory or 10x evidence is available for this correction. The pending validation
scope is recorded in `native-persisted-work-accounting/checks/annotation-pending.md`.

### Diagnostic measurement outcome — early stop for missing exact prefilter

The first nine completed pairs provide three observations per case and state.
An unchanged summarizer preserves all 1,267 cases and reports insufficient
samples, original case 821 failures and no acceptance. Its per-case medians
identify substantial slow cases: startup-prepared case 874 is main 0.4505 ms
versus Go 275.875 ms; case 875 is 1.224917 ms versus 291.762333 ms; ordered-skip
case 830 is 753.395 ms versus 4626.915959 ms. These are diagnostic medians,
not P95 acceptance or a causal profile. Full records, failure provenance and
the fixed nine-pair snapshot are retained under
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-diagnostic-prefix-v1`.
Independent read-only review verifies the record hashes, all 144 selected raw
timings and 24 state/case median calculations. The complete unfiltered report
remains available; no across-case aggregate substitutes for acceptance.

Source review confirms that the Go wide-catalog `ParallelRaw` DISTINCT branch
scans complete property-string directories before detecting an empty match,
where main first uses retained/mapped exact matching string IDs and avoids
CallSite projection when all sets are empty. This is a concrete omitted branch
to repair, although no profile yet establishes its share of total time. The
repair must preserve main's reader validation, transformed-string semantics,
cache/work/cancellation boundaries, generic supplementation and source order.
Ordered-skip separately incurs complete UTF-16 conversions during string
comparison; its actual cost remains a profiling hypothesis.

To prioritize repairing this candidate, the diagnostic sampling controller is
deliberately sent SIGINT after recording the decision. Session 68471 terminates
with exit 130 and controller `stopped`; all 25 owned runtime receipts are
terminal. Twelve complete pairs (four per state) and the unpaired fifth
startup-prepared Go run are preserved. That final Go child also exits 1 for the
original gate; no trial is deleted, overwritten or silently replaced. A final
partial summary exits 1, retains insufficient samples and no acceptance. The
decision, controller snapshots and terminal audit are under
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-stop-decision-v1`.

This candidate has no completed 200-sample P95 result and no 10x claim. The
real64 fixture, baseline revision, frozen source identity, failed evidence and
full objective are unchanged. Correctness verification resumes after the
terminal audit; a later optimized candidate still requires independently
checked functionality, required benchmark gates and at least 200 valid
observations per case/runtime/state for final P95 acceptance.

### Functional verification — Annotation count correction

After the diagnostic campaign's terminal boundary, the old production decoder
is run through a Go overlay and reproduces the original bad-tail failure in
both candidate APIs, along with its negative-count failures. The corrected
candidate passes 30 race-enabled top-level Store executions (ten tests repeated
three times) and all three original public Annotation cases, including exact
error/empty-result and before/after retained/mapped state. No production changes
are made in response to these checks. The original failures remain archived.

Full context, module race and vet checks pass with 3,397 unchanged recorded
inputs. A complete 2,577-file checked source is frozen externally as
`persisted-work-f0838dda-annotation-source-v1/module`; the only differences from
the checked Enum source are `projection_node.go`, new
`projection_annotation_test.go`, and `generic_disjunction_test.go`.

The full 201-case replay compiles/captures successfully and retains comparison
exit 1. Both main references now match 170/181 public cases and 19/20 provider
controls: 12 strict differences remain. Exactly three projected case records
change from the prior Enum capture, all the intended Annotation bad-tail cases;
they now reach the actual SID 28/list 21 failure. Original fixture files and
the reference comparator are unchanged, with the same 22 generated sidecars
preserved. The declared provider-wrapper and diagnostics limitations remain.

Evidence is retained under `checks/annotation-focused-evidence`,
`checks/annotation-fullchecks` and `checks/annotation-matrix201`. A new real64
cold/warm/startup correctness replay has started from the checked source;
three-state completion is not yet claimed. This correction has no measured
P95, CPU/memory improvement or 10x evidence; the prior diagnostic campaign
measured a different, pre-Annotation source.

The subsequent real64 cold replay is terminal with all 1,267 declared public
results matching and the original 821 failure retained, but it fails state
comparison: 2,527 mapped-range-count differences start at case 3 after, on
fixture-android-08 (main 4, Go 0). This is the previously observed lazy-core
failure pattern; it does not establish an Annotation-induced scheduling cause.
All 1,152 original files and 2,577 frozen source files remain unchanged. The
driver stops on cold, so warm and startup-prepared are unexecuted. All twelve
terminal capture artifacts, including the failed verifier, are preserved under
`checks/annotation-real64-cold`. The failed state comparison remains a failed
comparison; no acceptance claim, comparator relaxation or retry-until-pass is
made. The three corrected Annotation matrix cases do not establish full server
or real64 state parity.

### WIP archive — Split DISTINCT exact prefilter integration (2026-09-09)

The user requested a commit and remote-branch archive while the next repair was
being integrated. This snapshot preserves the preceding work-accounting,
reader/cache, executor and decoder changes and their successful and failed
verification records. It is an archive checkpoint, not an accepted performance
candidate or a claim of whole-server parity.

The current DISTINCT repair adds explicit Split load-only selection, retained
versus mapped exact matching, early empty-result handling, raw fallback without
an extra optional reader, and the final retained load/build entry. Raw workers
now use invocation-local shared matching states, four-field reads with storage
work boundaries, and storage tuple keys. Selected tuple preflight uses cached
string-ID lookup and property membership without materializing node postings.
Generic supplementation remains after the CallSite path.

At this archive boundary, `go test ./internal/query ./internal/store -run
'^TestDistinctSplit' -count=1 -timeout=180s` with Go 1.22.0 passes; the Store
package compiles but that exact pattern selects no Store tests. The independent
Store race run covering MainDistinct, MainBuild, MainOrdinary, ProjectionContext
and DistinctProjection also passes. These checks do not replace the complete
module/context/race/vet checks or fresh full JVM/real64 comparison for this new
integration. Worker-pool and parent-interruption semantics still require review.

The last fully checked Annotation source retains the previously documented
12/201 strict matrix differences and real64 cold cache-state failure despite
all 1,267 declared public comparisons matching. The current integration has no
new real64 latency, CPU or memory result. The stopped Enum measurements apply
only to their original frozen source. Final acceptance remains complete
functional reproduction and at least 200 samples per case/runtime/state showing
Go P95 no more than one tenth of pinned main; no gate or failure is waived.

Before committing, the independent query raw-worker race run also finishes
successfully on an unchanged 2,582-file frozen module, including raw tuple keys
under duplicate aliases. The two Store focused runs and query raw-worker run
are archived with byte hashes under `checks/distinct-split-wip`. None is a new
public JVM oracle or performance result. Read-only review additionally confirms
that shared segment-pool ownership, join-interruption precedence and configured
Split consumer selection remain unfinished; this WIP explicitly retains those
gaps for the next implementation and verification step.
