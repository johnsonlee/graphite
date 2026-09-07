# Filtered node pagination and ORDER integration

This functional correction preserves main's SKIP-before-projection, bounded
ORDER heap, retained/evicted DISTINCT semantics, graph routing, source waves,
encounter ties and cancellation. It repairs all28 B cases in the original1048
corpus, reaching1044 equal, and fixes an additional successful-row discrepancy
on the real64 corpus. It does not establish full product parity or10x main P95.

## Tested source and behavior

Root base is `62b92d2061b72150d76767cd07c18e351dddc7a3` (CDE plus Attempt15).
The exact author incremental patch is
`58f4e291d0a429d0f2f7a7e01cd78557ce164b041a01ce8ef63e3e3cf29b56a6`.
All32 changed files match the author;2401 unaffected module files preserve
Attempts12/13/15, exact tuples and CDE. The three production files are engine.go,
streaming_pagination.go and streaming_order.go. Root2433 inputs exactly equal
both independently tested and helper-free HTTP candidate sources.

Author647, original author review73 (inside provider), root integration260 and
independent combined review306 files retain their frozen manifests. The root
independent review checked all2402 baseline files against committed Git blobs.
The final module inventory is f051bc14f55140b99dbddd46e2d4406f44cf60bbead85a0b527ac0cef2e58cfd.
The complete integration archive also includes58 fixed graphite-explore inputs
for OpenAPI drift checks. Its first missing external dependency failure remains;
archive_inputs.py now includes these dependencies explicitly.

The main comparison is pinned4e328b0109e13c896b74004823fb049fcb19251a,
Java17.0.18 ARM64, JAR SHA91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d.
Node-only admission requires filtered single MATCH/RETURN and literal LIMIT;
Method, relationships, multiple patterns, OPTIONAL and named paths are outside
this block. Unknown labels retain general suffix evaluation. Nonliteral SKIP is
ignored only by the admitted streaming planner. Qualified DISTINCT drains for
provenance. ORDER evaluates every required projection; retained duplicates skip
ORDER while evicted keys evaluate it again. Worker failures cancel and join.
Graph scope precedes dispatch; storage sees the selected pipeline source count.

## Correctness evidence

Fullmodule race/vet pass, plus independent A15/tuple/B cancellation and joining
checks. Original1048 improves1016->1044, exactly28 repaired and four prior
wrapper-empty-term differences retained. These four are not the entire product
gap inventory. Generic fault432 improves408->432;24 candidate responses change,
while nine additional records change only their internal current-build control.

All595 new complete JSON-value responses and access traces pass. Numeric
spellings remain distinct evidence:166 leaves differ (8rows,98parameters,60spec),
not raw-byte parity; strings/bools/null and array order are never normalized.
Across67 old artifact captures,61 remain exact,3 have the documented functional
repairs, and3 have only76 speculative mappedView lifecycle leaves. Original raw
outputs and every comparison are preserved.

## Real64 requests and observed costs

The fixture is all64 persisted graphs at /tmp/pr113-exp037-fixture.nXn4fg,
1152 files/10,338,207,518 bytes. Separate APFS COW main/native/HTTP clones keep
source data intact. Fresh main HTTP capture executes the previous13-query
history followed by eight grounded B queries; all21 HTTP responses succeed and
the old13 full bodies remain unchanged. Query preparation proves B AST admission
without evaluating Stores. The direct ORDER parallel choice still depends on
actual storage strategy; an eviction event is not inferred merely from syntax.

The original strict Go baseline diagnostic stopped at the19th query after a
complete successful-body mismatch. It exited2 before Close; the output, stack,
identity and post-exit source/binary/fixture checks remain in base/. The collecting
sweep is a diagnostic-only harness revision, used identically for fresh base and
candidate. It executes the unchanged full query history, records every mismatch,
closes all Stores and still exits2 for any mismatch. No query, production code
or expected body was changed to make the old revision pass.

Fresh full sweeps: native base20/21 match, candidate21/21 match. For the small
DISTINCT ORDER query the old base returns <init>/getInstance, while main and the
candidate return normalizeKey/purgeByMessageBox. That row-error base is not a
valid latency comparison. This extends real-data correctness evidence beyond
the earlier1048 suite without removing its remaining cases.

Commands/scripts/configs/full bodies/counter receipts/profiles/source/binary
identities are retained. Go1.22.0 darwin/arm64,16 logical CPUs,64GB host;
asynchronous CPU sampling off, getrusage CPU, forced GC outside request counters.
These are single instrumented, sequential same-history observations on a shared
host; correctness/build work could overlap. Prior prefix requests warm indexes,
so later requests are not independent cold measurements.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes | Valid output pair |
|---|---:|---:|---:|---:|---:|---:|---|
| wrapped-firstLastGraphBimodalClassPrefix | 13.552867 | 15.079963 | 66.656043 | 72.045276 | 5966112272 | 5966070080 | yes |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 10.028290 | 11.577072 | 46.654226 | 54.522829 | 444269984 | 444182176 | yes |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.303734 | 0.338740 | 2.017335 | 2.232007 | 268471264 | 268465504 | yes |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.311562 | 0.347560 | 2.079391 | 2.306256 | 268425280 | 268442896 | yes |
| global-wide-distribution-broad-all-64 | 0.001920 | 0.002105 | 0.001923 | 0.002059 | 1636304 | 1634608 | yes |
| c-necessary-ordinary | 0.002324 | 0.002505 | 0.002351 | 0.002548 | 2697960 | 2698248 | yes |
| c-necessary-ordinary-repeat | 0.001665 | 0.001966 | 0.001696 | 0.001988 | 2272248 | 2271208 | yes |
| c-necessary-distinct | 2.109622 | 2.661941 | 10.108552 | 12.850871 | 1192747488 | 1192770464 | yes |
| c-necessary-distinct-repeat | 2.088874 | 2.318559 | 9.933364 | 11.191950 | 1172738112 | 1172743576 | yes |
| d-unbounded-android | 0.091332 | 0.095835 | 0.091267 | 0.095615 | 137484848 | 137480208 | yes |
| d-unbounded-kotlin | 0.057452 | 0.055536 | 0.057538 | 0.055603 | 82167472 | 82173600 | yes |
| e-generic-ordinary | 0.000554 | 0.000557 | 0.000583 | 0.000596 | 779800 | 780616 | yes |
| e-generic-distinct | 6.495470 | 6.639643 | 6.471130 | 6.623509 | 8392853320 | 8392826824 | yes |
| b-ordinary-skip | 13.296867 | 0.001228 | 13.348855 | 0.001270 | 3812181784 | 1043152 | yes |
| b-distinct-skip | 1.268922 | 1.535125 | 1.284297 | 1.576204 | 1081987720 | 1620267304 | yes |
| b-ordinary-order-parallel | 0.017963 | 0.001457 | 0.018008 | 0.004711 | 9566080 | 22791224 | yes |
| b-ordinary-order-generic-serial | 11.813967 | 0.171036 | 16.389199 | 0.171067 | 12760607808 | 261524016 | yes |
| b-distinct-order-retained | 0.017785 | 0.002595 | 0.019906 | 0.002618 | 9719544 | 22487256 | yes |
| b-distinct-order-eviction-candidate | 0.018034 | 0.002638 | 0.020797 | 0.002663 | 9732536 | 22527136 | **no: base rows wrong** |
| b-order-duplicate-alias-ties | 0.018051 | 0.001385 | 0.018123 | 0.004660 | 9543472 | 22751216 | yes |
| b-distinct-order-callee | 0.017808 | 0.002698 | 0.017843 | 0.002743 | 9788128 | 22581224 | yes |

Ordinary SKIP and routed generic ORDER remove large materialization costs.
DISTINCT SKIP regresses from1.269 to1.535 seconds and1.082 to1.620GB allocated.
Other direct ORDER cases use roughly22.5MB instead of9.6MB despite shorter wall
time. Prefix and several unchanged controls are slower in this pair; the cause
is not established. Keep the functional correction with these costs visible;
this is not a blanket performance-regression-free claim. Wrong baseline output
is explicitly excluded only from speed comparisons, never from correctness.
No observation establishes P95, peak RSS, or a speedup against main.

## Shipping command and fixture verification

The actual helper-free command was built from2433 module inputs and152 active
compiler/embed inputs. Binary SHA256:
7368c68a255c9a4ac6a1812da09361b6d94b6dd80cbb79a54c4e199f7ea3c787.
Two fresh processes returned63/63 complete typed main bodies, headers and64-graph
catalogs (21 new-history plus42 original regressions). Default60s timeout and
capacity4 remained. Both exited143 after SIGTERM, without forced kill; both ports
were independently bound after exit. Source and binary identities stayed fixed.

After the main/full Go sweeps and actual HTTP processes exited, original/main/
native/HTTP1152 graph file hashes remain unchanged, with no extra graph files.
The first interrupted strict diagnostic remains classified separately from the
two complete sweeps. Main and native raw runtime/exit receipts are retained.

## Remaining scope

This block does not repair relationship consumers, lazy edge sources, generic
CDE EAGER strategy dispatch, global JVM work/memory budgets or all distribution/
observability contracts. Deterministic Java17 compressed-class array bounds are
ported, but environment-dependent Java heap exhaustion is not modeled. Main
Class identity ordering is not a unique cross-JVM row/LIMIT contract; its four
existing discrepancies remain visible. The final repeated main-relative P95
protocol and required benchmark-regression-gate remain open.
