# Attempt18: reuse an unregistered generic candidate slot

Keep the single generic-loop allocation optimization. The real64 generic DISTINCT
query records 5.391398->4.900908s, CPU5.385597->4.896566s, allocation5,479,520,024->2,249,485,384bytes.
These are one instrumented pair against the preceding Go revision, not P95 or
main-relative speedup evidence. All controls and raw request counters are retained.

## Exact change and independent verification

Base is fb4434df, including A17. Patch
259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d
changes only the generic lazyFiltered loop in production. candidate.go updates
its borrowing comment; lazy_filtered_slot_test.go is new. The existing candidateSlot
is reused only when rowOrders is nil. Each iteration still decodes the complete
owned node before the same matching/WHERE/projection and later-source consumption.
When the registry is active, nodeValue retains the original owned-value path.
No map, source selection, decoder, cache, task, equality or cancellation changes
are included. All A17 Store checkpoint files remain exact base bytes.

The276-file author freeze and189-file independent integration are hash-verified.
All2437 module inputs and59 external references match the fully tested combination
and root. Whole-module race/vet pass. Original1048 stays1044/F4, B595 full responses
and53 traces pass, with166 numeric spelling differences explicitly preserved.
Of76 historical artifacts,73 are raw exact; three have only51 enumerated mappedView
boolean leaves in40-source histories. No retained or public response differences
occurred in the independent integration.

Whole values freeze at lazyProject, while nested containers freeze at their
existing construction boundaries. freezeCandidate is shallow; recursive output
ownership is established by those boundaries, not an assumed recursive freeze.
Five final new top-level tests plus152 subtests actually pass against the exact
original-production overlay. A disabled-registry-guard counterfactual fails the
escape test, proving that test detects the hazardous path. Eighteen independent
named race executions cover distinct Stores with repeated IDs, graph identity,
inline bindings/comprehensions, nested values after Close, cancellation, fresh
requests and worker joining. Full decoded-field errors and late/overwritten
projection errors remain observable even when LIMIT or DISTINCT could hide them.

Initial test failures are retained: sqrt('late') used the existing conversion
fallback and did not throw, so the test uses the actually throwing left call;
an independent test used incorrectly cased exported fields and failed compilation.
Only these test premises/field names changed. Final production required no repair.

## Allocation diagnosis

The65-file read-only A16 audit identifies sampled generic DISTINCT allocation:
3,334,933,470bytes (59.81%) at nodeValue boxing in the generic loop. Six pprof
commands inspect existing real64 profiles; no synthetic performance run exists.
That allocation share is not a CPU share or speed prediction. Decoder parameters,
binding maps and DISTINCT keys are separate operations. The source audit's old
sqrt throwing example is explicitly corrected by the author evidence above.

## Real persisted64 pair

Both fresh Go processes execute the same frozen21-query B history on all64 real
graphs from /tmp/pr113-exp037-fixture.nXn4fg,1152files/10,338,207,518bytes.
All42 complete typed bodies equal the unchanged pinned-main HTTP responses;
all before/after source histories agree. Both processes close Stores and exit0.
Original and native clone hashes, binaries and source inputs remain unchanged.

Go1.22.0 darwin/arm64,16 logical CPUs,64GB host; no asynchronous CPU sampler.
getrusage CPU and allocation counters surround execute+marshal; forced GC stays
outside request counters. All three known agents confirmed heavy processes
terminal before the pair. External host activity is uncontrolled. Later queries
inherit the fixed earlier history and are not independent cold measurements.
Exact commands, environment, source identities, full responses and request
counters are in run-pair.py,run-profile.py,run-results.json and each receipt.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 1.620068 | 1.705973 | 12.536455 | 13.830234 | 5965824120 | 5965814536 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 0.948603 | 1.006266 | 6.979380 | 7.420089 | 443945336 | 443936888 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.299469 | 0.308904 | 1.969256 | 2.018689 | 268474592 | 268465248 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.305769 | 0.318127 | 1.975691 | 2.150681 | 268438296 | 268425552 |
| global-wide-distribution-broad-all-64 | 0.001823 | 0.001819 | 0.001841 | 0.001849 | 1635168 | 1636480 |
| c-necessary-ordinary | 0.002344 | 0.002323 | 0.002370 | 0.002354 | 2684328 | 2687000 |
| c-necessary-ordinary-repeat | 0.001681 | 0.001719 | 0.001722 | 0.001761 | 2258664 | 2258296 |
| c-necessary-distinct | 0.211233 | 0.209407 | 1.560417 | 1.542012 | 1192602984 | 1192604840 |
| c-necessary-distinct-repeat | 0.172603 | 0.174077 | 1.279898 | 1.283272 | 1172595536 | 1172597952 |
| d-unbounded-android | 0.074303 | 0.067068 | 0.074272 | 0.067060 | 79409296 | 15021024 |
| d-unbounded-kotlin | 0.044406 | 0.040840 | 0.044426 | 0.040844 | 47950624 | 10026672 |
| e-generic-ordinary | 0.000529 | 0.000499 | 0.000558 | 0.000535 | 687528 | 603064 |
| e-generic-distinct | 5.391398 | 4.900908 | 5.385597 | 4.896566 | 5479520024 | 2249485384 |
| b-ordinary-skip | 0.001112 | 0.001125 | 0.001144 | 0.001128 | 1032384 | 1033808 |
| b-distinct-skip | 1.519602 | 1.508027 | 1.516301 | 1.507134 | 1620066328 | 1620064008 |
| b-ordinary-order-parallel | 0.001403 | 0.001501 | 0.004637 | 0.004626 | 22600328 | 22601112 |
| b-ordinary-order-generic-serial | 0.152273 | 0.153736 | 0.152317 | 0.153555 | 203450048 | 203450912 |
| b-distinct-order-retained | 0.002532 | 0.002603 | 0.002566 | 0.002634 | 22295688 | 22294456 |
| b-distinct-order-eviction-candidate | 0.002564 | 0.002645 | 0.002610 | 0.002680 | 22337744 | 22334128 |
| b-order-duplicate-alias-ties | 0.001394 | 0.001441 | 0.004505 | 0.004630 | 22558768 | 22553792 |
| b-distinct-order-callee | 0.002631 | 0.002701 | 0.002683 | 0.002752 | 22390072 | 22389160 |

## Actual shipping HTTP verification and decision

The helper-free shipping binary SHA256 is 5e49736246145552762f1e8a329d50691aba2d33dcad993b3e3f876e724eb6d1.
All152 active compiler/embed inputs and2437 module inputs match the tested/root
candidate. Two fresh server processes pass63 complete typed main bodies, headers
and64-graph catalogs, using default60-second timeout and capacity4. Both exit143
after SIGTERM without forced kill and release their ports. All1152 original/HTTP
clone files retain their hashes without extras after the processes exit.

The initial independent port-bind probe failed after both recorded exits. Its
failure and subsequent no-listener/refused-connect/successful-bind observations
are retained. The unchanged verifier then passes without restarting a server,
changing sources or changing the probe's socket options.

Keep one candidate-slot optimization in this commit. Slightly slower controls,
GC counters and all raw observations remain visible; no blanket no-regression,
peak-RSS or GC-pause improvement is inferred. This does not establish100% language,
source/runtime compatibility or main-relative10x P95. Relationship/edge integration,
remaining runtime/ordering boundaries and the repeated full acceptance matrix plus
required benchmark-regression-gate remain outstanding.
